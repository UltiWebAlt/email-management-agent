package com.ultiweb.jobs.svc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates reading unread mail, AI analysis, and Gmail label application.
 */
@Service
public class EmailTriageSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(EmailTriageSvc.class);
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

	public synchronized List<EmailTriageResult> processUnreadEmails() throws IOException {
		final List<EmailMessage> emails = emailReader.readUnreadEmails();
		final List<EmailTriageResult> results = new ArrayList<>();
		int skipped = 0;
		int analyzed = 0;
		int failed = 0;
		LOGGER.info("Retrieved {} unread emails for triage.", emails.size());
		for (final EmailMessage email : emails) {
			final MessageKey key = new MessageKey(email.account(), email.id());
			if (processedMessages.contains(key)) {
				skipped++;
				continue;
			}
			try {
				processEmail(email).ifPresent(results::add);
				processedMessages.add(key);
				analyzed++;
			} catch (final RuntimeException exception) {
				failed++;
				LOGGER.error("Triage failed for account={}, message={}; retrying on the next poll.",
						email.account(), email.id(), exception);
			}
		}
		LOGGER.info("Triage complete: analyzed={}, already processed={}, failed={}, labels applied={}.",
				analyzed, skipped, failed, results.size());
		return List.copyOf(results);
	}

	private Optional<EmailTriageResult> processEmail(final EmailMessage email) {
		LOGGER.info("Requesting AI summary for account={}, message={}, subject={}", email.account(), email.id(), email.subject());
		final String summary = emailSummarySvc.summarize(email);
		LOGGER.info("Requesting AI label recommendation for account={}, message={}", email.account(), email.id());
		final Optional<String> suggestedLabel = emailTagSvc.suggestTag(email, summary);
		if (suggestedLabel.isEmpty()) {
			LOGGER.info("Analyzed account={}, message={}, summary={}, recommendedLabel=NONE",
					email.account(), email.id(), summary);
			return Optional.empty();
		}
		final String labelName = suggestedLabel.get();
		try {
			emailLabelWriter.applyLabel(email.account(), email.id(), labelName);
		} catch (final IOException exception) {
			throw new EmailTriageException("Unable to apply label to message " + email.id() + " for account " + email.account(), exception);
		}
		LOGGER.info("Applied label: account={}, message={}, summary={}, label={}", email.account(), email.id(), summary, labelName);
		return Optional.of(new EmailTriageResult(email.account(), email.id(), summary, labelName));
	}

	private record MessageKey(String account, String messageId) {
	}
}
