package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.email.GmailDraftSvc;
import com.ultiweb.jobs.svc.email.GmailSentMessage;
import com.ultiweb.jobs.svc.email.EmailWorkflowLabels;
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseSelectionChange;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ResponsePreparationWorkflowSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(ResponsePreparationWorkflowSvc.class);
	private final JobResponseRepository repository;
	private final DeepInfraEmailReplyGenerator replyGenerator;
	private final GmailDraftSvc gmailDraftSvc;
	private final Executor executor;
	private final Clock clock;
	private final boolean developmentMode;
	private final boolean productionMode;
	private final ApplicationEventPublisher eventPublisher;

	@Autowired
	public ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			@Qualifier("responseExecutor") final Executor executor, final Environment environment,
			final ApplicationEventPublisher eventPublisher) {
		this(repository, replyGenerator, gmailDraftSvc, executor, Clock.systemUTC(),
			environment.acceptsProfiles(Profiles.of("dev")),
			environment.acceptsProfiles(Profiles.of("production")), eventPublisher);
	}

	ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			final Executor executor, final Clock clock, final boolean developmentMode) {
		this(repository, replyGenerator, gmailDraftSvc, executor, clock, developmentMode, !developmentMode, event -> { });
	}

	ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			final Executor executor, final Clock clock, final boolean developmentMode,
			final ApplicationEventPublisher eventPublisher) {
		this(repository, replyGenerator, gmailDraftSvc, executor, clock, developmentMode, !developmentMode, eventPublisher);
	}

	ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			final Executor executor, final Clock clock, final boolean developmentMode, final boolean productionMode,
			final ApplicationEventPublisher eventPublisher) {
		this.repository = repository;
		this.replyGenerator = replyGenerator;
		this.gmailDraftSvc = gmailDraftSvc;
		this.executor = executor;
		this.clock = clock;
		this.developmentMode = developmentMode;
		this.productionMode = productionMode;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public ResponseSelectionResult save(final List<DashboardSelectionChange> changes) {
		final Set<Long> positionIds = new HashSet<>();
		for (final DashboardSelectionChange change : changes) {
			if (change.positionId() < 1 || !positionIds.add(change.positionId())) {
				throw new ResponseSelectionException("Selection changes must contain unique, positive position IDs");
			}
		}
		final int updated = repository.updateSelections(changes.stream()
				.map(change -> new ResponseSelectionChange(change.positionId(), change.selected())).toList());
		if (updated != changes.size()) {
			throw new ResponseSelectionException("One or more selected opportunities no longer exist");
		}
		final List<Long> eligible = repository.findSelectedWithoutResponse();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				eligible.forEach(positionId -> executor.execute(() -> prepareResponse(positionId)));
			}
		});
		return new ResponseSelectionResult(updated, eligible.size(), developmentMode ? "development" : "gmail-drafts");
	}

	@Transactional
	public ResponseSelectionResult prepare(final long positionId) {
		if (positionId < 1) {
			throw new ResponseSelectionException("Position ID must be positive");
		}
		final int updated = repository.updateSelections(List.of(new ResponseSelectionChange(positionId, true)));
		if (updated != 1 || !repository.findSelectedWithoutResponse().contains(positionId)) {
			throw new ResponseSelectionException("This opportunity cannot prepare another response");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				executor.execute(() -> prepareResponse(positionId));
			}
		});
		return new ResponseSelectionResult(1, 1, developmentMode ? "development" : "gmail-drafts");
	}

	public boolean send(final long positionId) throws IOException, MessagingException {
		final var candidate = repository.claimResponseForSending(positionId, clock.instant(),
				"<" + UUID.randomUUID() + "@email-management-agent.invalid>",
				clock.instant().minus(Duration.ofMinutes(5)));
		if (candidate.isEmpty()) {
			return false;
		}
		final var response = candidate.orElseThrow();
		final String recipient = productionMode ? response.recruiterEmail() : response.sourceAccount();
		boolean attemptStarted = response.reconcileOnly();
		try {
			if (response.reconcileOnly()) {
				final var existing = gmailDraftSvc.findSentReply(response.sourceAccount(),
						response.sendIdentifier(), recipient);
				if (existing.isEmpty()) {
					repository.markResponseSendUnconfirmed(positionId, clock.instant());
					notifyResponseUpdated(positionId);
					return false;
				}
				final GmailSentMessage sent = existing.orElseThrow();
				repository.markResponseSent(positionId, response.gmailDraftId(), sent.messageId(), sent.threadId(),
						"SENT", sent.sentAt() == null ? clock.instant() : sent.sentAt());
				notifyResponseUpdated(positionId);
				return true;
			}
			String draftId = response.gmailDraftId();
			if (draftId == null || draftId.isBlank()) {
				draftId = gmailDraftSvc.createDraft(response.sourceAccount(), response.recruiterEmail(),
						replySubject(response.sourceSubject()), response.responseContent());
				repository.saveSendingDraftId(positionId, draftId, clock.instant());
			}
			final String label = productionMode ? null : EmailWorkflowLabels.EMAIL_AGENT_TEST;
			repository.markSendAttemptStarted(positionId, clock.instant());
			attemptStarted = true;
			final GmailSentMessage sent = gmailDraftSvc.sendReply(response.sourceAccount(), draftId,
					recipient, replySubject(response.sourceSubject()), response.responseContent(), label, !productionMode,
					response.sendIdentifier());
			repository.saveSendingDraftId(positionId, sent.draftId(), clock.instant());
			final var sentAt = sent.sentAt() == null ? clock.instant() : sent.sentAt();
			final String status = !productionMode && !sent.labelApplied() ? "SENT_LABEL_FAILED" : "SENT";
			repository.markResponseSent(positionId, sent.draftId(), sent.messageId(), sent.threadId(), status, sentAt);
			notifyResponseUpdated(positionId);
			LOGGER.info("Sent Gmail response for position {} (message {}).", positionId, sent.messageId());
			return true;
		} catch (final IOException | MessagingException exception) {
			if (attemptStarted && !definitelyRejected(exception)) {
				repository.markResponseSendUnconfirmed(positionId, clock.instant());
			} else {
				repository.markResponseSendFailed(positionId, clock.instant());
			}
			notifyResponseUpdated(positionId);
			throw exception;
		}
	}

	private static boolean definitelyRejected(final Exception exception) {
		if (exception instanceof MessagingException) {
			return true;
		}
		if (exception instanceof GoogleJsonResponseException gmailException) {
			final int status = gmailException.getStatusCode();
			return status >= 400 && status < 500 && status != 408 && status != 429;
		}
		return false;
	}

	private void prepareResponse(final long positionId) {
		final var candidate = repository.claimResponse(positionId, clock.instant());
		if (candidate.isEmpty()) {
			return;
		}
		try {
			final var job = candidate.orElseThrow();
			final String body = replyGenerator.generate(job);
			if (!repository.isSelected(positionId)) {
				repository.markResponseFailed(positionId, clock.instant());
				return;
			}
			if (developmentMode) {
				repository.saveGeneratedResponse(positionId, body, "SAVED_FOR_REVIEW", clock.instant());
				notifyResponseUpdated(positionId);
				LOGGER.info("Stored generated response in the development database for position {}.", positionId);
				return;
			}
			repository.saveGeneratedResponse(positionId, body, "GENERATED", clock.instant());
			if (!repository.isSelected(positionId)) {
				repository.markResponseFailed(positionId, clock.instant());
				return;
			}
			final String recipient = productionMode ? job.recruiterEmail() : job.sourceAccount();
			final String draftId = gmailDraftSvc.createDraft(job.sourceAccount(), recipient,
					replySubject(job.sourceSubject()), body);
			repository.saveDraftId(positionId, draftId, clock.instant());
			notifyResponseUpdated(positionId);
			LOGGER.info("Created Gmail draft for position {}.", positionId);
		} catch (final Exception exception) {
			repository.markResponseFailed(positionId, clock.instant());
			notifyResponseUpdated(positionId);
			LOGGER.error("Unable to prepare response for position {} ({}).", positionId,
					exception.getClass().getSimpleName());
		}
	}

	private void notifyResponseUpdated(final long positionId) {
		eventPublisher.publishEvent(new DashboardResponseUpdatedEvent(positionId));
	}

	private static String replySubject(final String sourceSubject) {
		if (sourceSubject == null || sourceSubject.isBlank()) {
			return "Re: Architect job opportunity";
		}
		final String subject = sourceSubject.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return subject.regionMatches(true, 0, "Re:", 0, 3) ? subject : "Re: " + subject;
	}
}
