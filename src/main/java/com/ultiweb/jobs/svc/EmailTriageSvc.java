package com.ultiweb.jobs.svc;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailLabelWriter;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.EmailReader;
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

	public EmailTriageSvc(final EmailReader emailReader, final EmailSummarySvc emailSummarySvc,
			final EmailTagSvc emailTagSvc, final EmailLabelWriter emailLabelWriter) {
		this.emailReader = emailReader;
		this.emailSummarySvc = emailSummarySvc;
		this.emailTagSvc = emailTagSvc;
		this.emailLabelWriter = emailLabelWriter;
	}

	public List<EmailTriageResult> processUnreadEmails() throws IOException {
		pollLock.lock();
		try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final List<EmailMessage> emails = emailReader.readUnreadEmails();
			final var pending = new LinkedHashMap<MessageKey, CompletableFuture<Optional<EmailTriageResult>>>();
			final List<EmailTriageResult> results = new ArrayList<>();
			int skipped = 0;
			int analyzed = 0;
			int failed = 0;
			LOGGER.info("Retrieved {} unread emails for triage.", emails.size());
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
					final var result = entry.getValue().join();
					if (result.isPresent()) {
						final var recommendation = result.get();
						// Keep Gmail writes serial to avoid racing label creation and OAuth access.
						emailLabelWriter.applyLabel(key.account(), key.messageId(), recommendation.labelName());
						results.add(recommendation);
						LOGGER.info("Applied label: account={}, message={}, label={}",
								key.account(), key.messageId(), recommendation.labelName());
					}
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

	private Optional<EmailTriageResult> analyzeEmail(final EmailMessage email) {
		LOGGER.info("Requesting AI summary for account={}, message={}, bodyCharacters={}",
				email.account(), email.id(), email.body().length());
		final String summary = emailSummarySvc.summarize(email);
		LOGGER.info("Requesting AI label recommendation for account={}, message={}", email.account(), email.id());
		final Optional<EmailTag> suggestedLabel = emailTagSvc.suggestTag(summary);
		LOGGER.info("Analyzed account={}, message={}, recommendedLabel={}",
				email.account(), email.id(), suggestedLabel.map(EmailTag::labelName).orElse("NONE"));
		return suggestedLabel.map(label -> new EmailTriageResult(email.account(), email.id(), summary, label.labelName()));
	}

	private record MessageKey(String account, String messageId) {
	}
}
