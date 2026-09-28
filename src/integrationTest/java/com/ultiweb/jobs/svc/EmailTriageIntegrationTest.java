package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"gmail.polling.enabled=false", "spring.datasource.url=jdbc:sqlite::memory:"})
@ActiveProfiles("ollama")
@EnabledIfEnvironmentVariable(named = "RUN_GMAIL_OLLAMA_INTEGRATION_TEST", matches = "true")
class EmailTriageIntegrationTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(EmailTriageIntegrationTest.class);
	private static final int EMAIL_LIMIT = 50;

	private final GmailMailboxSvc gmailMailboxSvc;
	private final EmailSummarySvc emailSummarySvc;
	private final EmailTagSvc emailTagSvc;

	@Autowired
	EmailTriageIntegrationTest(final GmailMailboxSvc gmailMailboxSvc, final EmailSummarySvc emailSummarySvc,
			final EmailTagSvc emailTagSvc) {
		this.gmailMailboxSvc = gmailMailboxSvc;
		this.emailSummarySvc = emailSummarySvc;
		this.emailTagSvc = emailTagSvc;
	}

	@Test
	void summarizesAndRecommendsLabelsForTheLatestFiftyEmails() throws Exception {
		final List<EmailMessage> emails = gmailMailboxSvc.readLatestEmails(EMAIL_LIMIT);

		for (final EmailMessage email : emails) {
			final String summary = emailSummarySvc.summarize(email);
			final String recommendedLabel = emailTagSvc.suggestTag(summary).map(EmailTag::labelName).orElse("NONE");
			LOGGER.info("Account={}, email id={}, subject={}, summary={}, recommendedLabel={}",
					email.account(), email.id(), boundedLogText(email.subject(), 300), boundedLogText(summary, 1_000), recommendedLabel);
		}

		assertTrue(emails.stream().collect(Collectors.groupingBy(EmailMessage::account,
				Collectors.counting())).values().stream().allMatch(count -> count <= EMAIL_LIMIT));
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
