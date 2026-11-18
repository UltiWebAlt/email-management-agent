package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.email.EmailWorkflowLabels;
import com.ultiweb.jobs.svc.email.GmailDraftSvc;
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseFollowUpCandidate;
import java.time.Clock;
import java.time.Duration;
import java.io.IOException;
import javax.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public final class ResponseFollowUpSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(ResponseFollowUpSvc.class);
	private static final Duration FOLLOW_UP_DELAY = Duration.ofHours(48);
	private final JobResponseRepository repository;
	private final GmailDraftSvc gmailDraftSvc;
	private final DeepInfraEmailReplyGenerator replyGenerator;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;
	private final boolean productionMode;

	@Autowired
	public ResponseFollowUpSvc(final JobResponseRepository repository, final GmailDraftSvc gmailDraftSvc,
			final DeepInfraEmailReplyGenerator replyGenerator, final ApplicationEventPublisher eventPublisher,
			final Environment environment) {
		this(repository, gmailDraftSvc, replyGenerator, eventPublisher, Clock.systemUTC(),
			environment.acceptsProfiles(Profiles.of("production")));
	}

	ResponseFollowUpSvc(final JobResponseRepository repository, final GmailDraftSvc gmailDraftSvc,
			final DeepInfraEmailReplyGenerator replyGenerator, final ApplicationEventPublisher eventPublisher,
			final Clock clock, final boolean productionMode) {
		this.repository = repository;
		this.gmailDraftSvc = gmailDraftSvc;
		this.replyGenerator = replyGenerator;
		this.eventPublisher = eventPublisher;
		this.clock = clock;
		this.productionMode = productionMode;
	}

	public void checkForResponsesAndSendDueFollowUps() {
		final var now = clock.instant();
		final var dueBefore = now.minus(FOLLOW_UP_DELAY);
		repository.releaseStaleFollowUpChecks(now.minus(Duration.ofHours(2)), now);
		for (final var candidate : repository.findResponsesForFollowUp(dueBefore)) {
			try {
				repository.claimFollowUp(candidate.positionId(), clock.instant(), dueBefore)
						.ifPresent(this::processClaimedFollowUp);
			} catch (final RuntimeException exception) {
				LOGGER.error("Could not claim follow-up for position {}.", candidate.positionId(), exception);
			}
		}
	}

	private void processClaimedFollowUp(final ResponseFollowUpCandidate candidate) {
		try {
			final var existingFollowUp = gmailDraftSvc.findExistingFollowUp(candidate.sourceAccount(),
					candidate.gmailThreadId(), candidate.positionId());
			if (existingFollowUp.isPresent() && candidate.followUpSentAt() == null) {
				final var sent = existingFollowUp.orElseThrow();
				repository.markFollowUpSent(candidate.positionId(), candidate.followUpContent(), sent.messageId(),
						sent.sentAt() == null ? clock.instant() : sent.sentAt());
				notifyResponseUpdated(candidate.positionId());
				return;
			}
			final var incomingReply = gmailDraftSvc.findExternalReply(candidate.sourceAccount(),
					candidate.gmailThreadId(), candidate.sentAt());
			if (incomingReply.isPresent()) {
				final var reply = incomingReply.orElseThrow();
				repository.markReplied(candidate.positionId(), reply.messageId(), reply.receivedAt());
				notifyResponseUpdated(candidate.positionId());
				LOGGER.info("Recruiter replied in the Gmail thread for position {}.", candidate.positionId());
				return;
			}
			if (candidate.followUpSentAt() != null) {
				repository.markFollowUpUnanswered(candidate.positionId(), clock.instant());
				return;
			}
			sendFollowUp(candidate);
		} catch (final IOException | MessagingException | RuntimeException exception) {
			repository.markFollowUpFailed(candidate.positionId(), clock.instant());
			notifyResponseUpdated(candidate.positionId());
			LOGGER.error("Unable to check or send follow-up for position {} ({}).",
					candidate.positionId(), exception.getClass().getSimpleName(), exception);
		}
	}

	private void sendFollowUp(final ResponseFollowUpCandidate candidate) throws IOException, MessagingException {
		final String body = candidate.followUpContent() == null || candidate.followUpContent().isBlank()
				? replyGenerator.generateFollowUp(candidate) : candidate.followUpContent();
		if (candidate.followUpContent() == null || candidate.followUpContent().isBlank()) {
			repository.storeFollowUpContent(candidate.positionId(), body, clock.instant());
		}
		final String recipient = productionMode ? candidate.recruiterEmail() : candidate.sourceAccount();
		final String label = productionMode ? null : EmailWorkflowLabels.EMAIL_AGENT_TEST;
		final var sent = gmailDraftSvc.sendFollowUp(candidate.sourceAccount(), recipient,
				candidate.gmailThreadId(), candidate.positionId(), candidate.gmailMessageId(),
				replySubject(candidate.sourceSubject()), body, label);
		repository.markFollowUpSent(candidate.positionId(), body, sent.messageId(),
				sent.sentAt() == null ? clock.instant() : sent.sentAt());
		notifyResponseUpdated(candidate.positionId());
		LOGGER.info("Sent automatic Gmail follow-up for position {} (message {}).",
				candidate.positionId(), sent.messageId());
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
