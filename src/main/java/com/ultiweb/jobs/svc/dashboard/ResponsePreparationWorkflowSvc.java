package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.email.GmailDraftSvc;
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseSelectionChange;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

	public ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			@Qualifier("responseExecutor") final Executor executor, final Environment environment) {
		this(repository, replyGenerator, gmailDraftSvc, executor, Clock.systemUTC(),
			environment.acceptsProfiles(Profiles.of("dev")));
	}

	ResponsePreparationWorkflowSvc(final JobResponseRepository repository,
			final DeepInfraEmailReplyGenerator replyGenerator, final GmailDraftSvc gmailDraftSvc,
			final Executor executor, final Clock clock, final boolean developmentMode) {
		this.repository = repository;
		this.replyGenerator = replyGenerator;
		this.gmailDraftSvc = gmailDraftSvc;
		this.executor = executor;
		this.clock = clock;
		this.developmentMode = developmentMode;
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
				LOGGER.info("Stored generated response in the development database for position {}.", positionId);
				return;
			}
			repository.saveGeneratedResponse(positionId, body, "GENERATED", clock.instant());
			if (!repository.isSelected(positionId)) {
				repository.markResponseFailed(positionId, clock.instant());
				return;
			}
			final String draftId = gmailDraftSvc.createDraft(job.sourceAccount(), job.recruiterEmail(),
					replySubject(job.sourceSubject()), body);
			repository.saveDraftId(positionId, draftId, clock.instant());
			LOGGER.info("Created Gmail draft for position {}.", positionId);
		} catch (final Exception exception) {
			repository.markResponseFailed(positionId, clock.instant());
			LOGGER.error("Unable to prepare response for position {} ({}).", positionId,
					exception.getClass().getSimpleName());
		}
	}

	private static String replySubject(final String sourceSubject) {
		if (sourceSubject == null || sourceSubject.isBlank()) {
			return "Re: Architect job opportunity";
		}
		final String subject = sourceSubject.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return subject.regionMatches(true, 0, "Re:", 0, 3) ? subject : "Re: " + subject;
	}
}
