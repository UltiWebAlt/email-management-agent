package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {"gmail.polling.enabled=false", "spring.ai.model.chat=openai"})
@ActiveProfiles("deepinfra")
@EnabledIfEnvironmentVariable(named = "RUN_GMAIL_DEEPINFRA_INTEGRATION_TEST", matches = "true")
class DeepInfraEmailTriageIntegrationTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(DeepInfraEmailTriageIntegrationTest.class);
	private static final int EMAIL_LIMIT = 50;

	private final GmailMailboxSvc gmailMailboxSvc;
	private final EmailSummarySvc emailSummarySvc;
	private final EmailTagSvc emailTagSvc;

	@Autowired
	DeepInfraEmailTriageIntegrationTest(final GmailMailboxSvc gmailMailboxSvc,
			final EmailSummarySvc emailSummarySvc, final EmailTagSvc emailTagSvc) {
		this.gmailMailboxSvc = gmailMailboxSvc;
		this.emailSummarySvc = emailSummarySvc;
		this.emailTagSvc = emailTagSvc;
	}

	@Test
	void summarizesLocallyAndRecommendsLabelsUsingDeepInfra() throws Exception {
		// given
		final List<EmailMessage> emails = gmailMailboxSvc.readLatestEmails(EMAIL_LIMIT);
		assertFalse(emails.isEmpty(), "No emails were returned; the live DeepInfra test needs at least one email");
		assertTrue(emails.stream().collect(Collectors.groupingBy(EmailMessage::account, Collectors.counting()))
				.values().stream().allMatch(count -> count <= EMAIL_LIMIT));

		try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final var analyses = emails.stream()
					.map(email -> CompletableFuture.runAsync(() -> analyze(email), executor))
					.toArray(CompletableFuture[]::new);
			// Wait for every message so one failure does not hide other recommendations.
			CompletableFuture.allOf(analyses).join();
		}
		LOGGER.info("Live DeepInfra analysis completed for {} emails; Gmail labels were not modified.", emails.size());
	}

	private void analyze(final EmailMessage email) {
		// when
		LOGGER.info("Requesting local Ollama summary for account={}, email id={}, bodyCharacters={}",
				email.account(), email.id(), email.body().length());
		final String summary = emailSummarySvc.summarize(email);

		// then
		assertNotNull(summary, "Ollama returned no summary for " + email.account() + "/" + email.id());
		assertFalse(summary.isBlank(), "Ollama returned a blank summary for " + email.account() + "/" + email.id());
		LOGGER.info("Sending local summary to DeepInfra for account={}, email id={}", email.account(), email.id());
		final String recommendedLabel = emailTagSvc.suggestTag(summary).map(EmailTag::labelName).orElse("NONE");
		LOGGER.info("Account={}, email id={}, subject={}, summary={}, recommendedLabel={}",
				email.account(), email.id(), boundedLogText(email.subject(), 300), boundedLogText(summary, 1_000), recommendedLabel);
	}

	private static String boundedLogText(final String value, final int maximumLength) {
		if (value == null) {
			return "";
		}
		final String normalized = value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return normalized.length() <= maximumLength
				? normalized
				: normalized.substring(0, maximumLength - 3) + "...";
	}
}
