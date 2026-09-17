package com.ultiweb.jobs.svc;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Coordinates reading unread mail, AI analysis, and Gmail label application.
 */
@Service
public class EmailTriageSvc {
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
		return emailReader.readUnreadEmails().stream()
				.map(this::processEmail)
				.flatMap(Optional::stream)
				.toList();
	}

	private Optional<EmailTriageResult> processEmail(final EmailMessage email) {
		final String summary = emailSummarySvc.summarize(email);
		final Optional<String> suggestedLabel = emailTagSvc.suggestTag(email, summary);
		if (suggestedLabel.isEmpty()) {
			return Optional.empty();
		}
		final String labelName = suggestedLabel.get();
		try {
			emailLabelWriter.applyLabel(email.id(), labelName);
		} catch (final IOException exception) {
			throw new EmailTriageException("Unable to apply label to message " + email.id(), exception);
		}
		return Optional.of(new EmailTriageResult(email.id(), summary, labelName));
	}
}
