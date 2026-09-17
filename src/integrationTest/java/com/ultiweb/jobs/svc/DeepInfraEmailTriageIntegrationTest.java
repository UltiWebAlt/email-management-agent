package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
	void summarizesAndRecommendsLabelsForTheLatestFiftyEmailsUsingDeepInfra() throws Exception {
		// given
		final List<EmailMessage> emails = gmailMailboxSvc.readLatestEmails(EMAIL_LIMIT);
		assertFalse(emails.isEmpty(), "No emails were returned; the live DeepInfra test needs at least one email");
		assertTrue(emails.stream().collect(Collectors.groupingBy(EmailMessage::account, Collectors.counting()))
				.values().stream().allMatch(count -> count <= EMAIL_LIMIT));

		for (final EmailMessage email : emails) {
			// when
			LOGGER.info("Requesting DeepInfra analysis for account={}, email id={}, subject={}",
					email.account(), email.id(), email.subject());
			final String summary = emailSummarySvc.summarize(email);

			// then
			assertNotNull(summary, "DeepInfra returned no summary for " + email.account() + "/" + email.id());
			assertFalse(summary.isBlank(), "DeepInfra returned a blank summary for " + email.account() + "/" + email.id());
			final String recommendedLabel = emailTagSvc.suggestTag(email, summary).orElse("NONE");
			LOGGER.info("Account={}, email id={}, from={}, subject={}, summary={}, recommendedLabel={}",
					email.account(), email.id(), email.from(), email.subject(), summary, recommendedLabel);
		}
		LOGGER.info("Live DeepInfra analysis completed for {} emails; Gmail labels were not modified.", emails.size());
	}
}
