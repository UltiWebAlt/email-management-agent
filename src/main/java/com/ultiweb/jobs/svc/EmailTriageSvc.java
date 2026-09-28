package com.ultiweb.jobs.svc;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailLabelWriter;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.EmailReader;
import com.ultiweb.jobs.svc.email.EmailWorkflowLabels;
import com.ultiweb.jobs.svc.persistence.ArchitectJobPersistenceSvc;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates reading unread mail, AI analysis, and Gmail label application.
 */
@Service
public class EmailTriageSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(EmailTriageSvc.class);
	private final ReentrantLock pollLock = new ReentrantLock();
	private final Set<MessageKey> processedMessages = new HashSet<>();
	private final EmailReader emailReader;
	private final EmailSummarySvc emailSummarySvc;
	private final EmailTagSvc emailTagSvc;
	private final EmailLabelWriter emailLabelWriter;
	private final ArchitectJobPersistenceSvc architectJobPersistenceSvc;

	public EmailTriageSvc(final EmailReader emailReader, final EmailSummarySvc emailSummarySvc,
			final EmailTagSvc emailTagSvc, final EmailLabelWriter emailLabelWriter,
			final ArchitectJobPersistenceSvc architectJobPersistenceSvc) {
		this.emailReader = emailReader;
		this.emailSummarySvc = emailSummarySvc;
		this.emailTagSvc = emailTagSvc;
		this.emailLabelWriter = emailLabelWriter;
		this.architectJobPersistenceSvc = architectJobPersistenceSvc;
	}

	public List<EmailTriageResult> processEmails() throws IOException {
		pollLock.lock();
		try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final List<EmailTriageResult> results = new ArrayList<>(persistManuallyTaggedArchitectJobs());
			final List<EmailMessage> emails = emailReader.readEmailsForTriage();
			final var pending = new LinkedHashMap<MessageKey, CompletableFuture<EmailAnalysis>>();
			int skipped = 0;
			int analyzed = 0;
			int failed = 0;
			LOGGER.info("Retrieved {} emails pending triage.", emails.size());
			for (final EmailMessage email : emails) {
				final MessageKey key = new MessageKey(email.account(), email.id());
				if (processedMessages.contains(key) || pending.containsKey(key)) {
					skipped++;
					continue;
				}
				pending.put(key, CompletableFuture.supplyAsync(() -> analyzeEmail(email), executor));
			}
			for (final var entry : pending.entrySet()) {
				final MessageKey key = entry.getKey();
				try {
					final EmailAnalysis analysis = entry.getValue().join();
					persistArchitectJob(analysis);
					applyWorkflowLabels(analysis);
					analysis.tag().map(tag -> new EmailTriageResult(analysis.email().account(), analysis.email().id(),
							analysis.summary(), tag.labelName())).ifPresent(results::add);
					processedMessages.add(key);
					analyzed++;
				} catch (final RuntimeException | IOException exception) {
					failed++;
					LOGGER.error("Triage failed for account={}, message={}; retrying on the next poll.",
							key.account(), key.messageId(), exception);
				}
			}
			LOGGER.info("Triage complete: analyzed={}, already processed={}, failed={}, labels applied={}.",
					analyzed, skipped, failed, results.size());
			return List.copyOf(results);
		} finally {
			pollLock.unlock();
		}
	}

	private List<EmailTriageResult> persistManuallyTaggedArchitectJobs() throws IOException {
		final List<EmailMessage> emails = emailReader.readArchitectEmailsPendingPersistence();
		final List<EmailTriageResult> results = new ArrayList<>();
		LOGGER.info("Retrieved {} manually tagged architect emails pending persistence.", emails.size());
		for (final EmailMessage email : emails) {
			final MessageKey key = new MessageKey(email.account(), email.id());
			try {
				if (!architectJobPersistenceSvc.isPersisted(email)) {
					final String summary = emailSummarySvc.summarize(email);
					architectJobPersistenceSvc.persist(email, summary);
					results.add(new EmailTriageResult(email.account(), email.id(), summary,
							EmailTag.ARCHITECT_JOBS.labelName()));
				}
				emailLabelWriter.applyLabel(email.account(), email.id(), EmailWorkflowLabels.ARCHITECT_PERSISTED);
				emailLabelWriter.applyLabel(email.account(), email.id(), EmailWorkflowLabels.PROCESSED);
				processedMessages.add(key);
				LOGGER.info("Persisted manually tagged architect email: account={}, message={}", email.account(), email.id());
			} catch (final RuntimeException | IOException exception) {
				LOGGER.error("Unable to persist manually tagged architect email: account={}, message={}; retrying next poll.",
						email.account(), email.id(), exception);
			}
		}
		return results;
	}

	private EmailAnalysis analyzeEmail(final EmailMessage email) {
		LOGGER.info("Requesting AI summary for account={}, message={}, bodyCharacters={}",
				email.account(), email.id(), email.body().length());
		final String summary = emailSummarySvc.summarize(email);
		LOGGER.info("Requesting AI label recommendation for account={}, message={}", email.account(), email.id());
		final Optional<EmailTag> suggestedLabel = emailTagSvc.suggestTag(summary);
		LOGGER.info("Analyzed account={}, message={}, recommendedLabel={}",
				email.account(), email.id(), suggestedLabel.map(EmailTag::labelName).orElse("NONE"));
		return new EmailAnalysis(email, summary, suggestedLabel);
	}

	private void persistArchitectJob(final EmailAnalysis analysis) {
		if (analysis.tag().filter(EmailTag.ARCHITECT_JOBS::equals).isPresent()) {
			architectJobPersistenceSvc.persist(analysis.email(), analysis.summary());
		}
	}

	private void applyWorkflowLabels(final EmailAnalysis analysis) throws IOException {
		final EmailMessage email = analysis.email();
		if (analysis.tag().isPresent()) {
			final EmailTag tag = analysis.tag().get();
			emailLabelWriter.applyLabel(email.account(), email.id(), tag.labelName());
			LOGGER.info("Applied label: account={}, message={}, label={}", email.account(), email.id(), tag.labelName());
			if (tag == EmailTag.ARCHITECT_JOBS) {
				emailLabelWriter.applyLabel(email.account(), email.id(), EmailWorkflowLabels.ARCHITECT_PERSISTED);
			}
		}
		emailLabelWriter.applyLabel(email.account(), email.id(), EmailWorkflowLabels.PROCESSED);
	}

	private record MessageKey(String account, String messageId) {
	}

	private record EmailAnalysis(EmailMessage email, String summary, Optional<EmailTag> tag) {
	}
}
