package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class EmailTriageSvcTest {

	@Test
	void processUnreadEmailsSummarizesAndAppliesTheSuggestedLabel() throws Exception {
		final EmailMessage firstEmail = new EmailMessage("message-1", "Invoice", "billing@example.com", "Your invoice is due.");
		final EmailMessage secondEmail = new EmailMessage("message-2", "Standup", "team@example.com", "The meeting is tomorrow.");
		final List<String> appliedLabels = new ArrayList<>();
		final EmailTriageSvc service = new EmailTriageSvc(
				() -> List.of(firstEmail, secondEmail),
				email -> "Summary of " + email.subject(),
				(email, summary) -> Optional.of(email.id().equals("message-1") ? "Dev Jobs" : "Architect Jobs"),
				(messageId, labelName) -> appliedLabels.add(messageId + ":" + labelName));

		final List<EmailTriageResult> results = service.processUnreadEmails();

		assertEquals(List.of(
				new EmailTriageResult("message-1", "Summary of Invoice", "Dev Jobs"),
				new EmailTriageResult("message-2", "Summary of Standup", "Architect Jobs")), results);
		assertEquals(List.of("message-1:Dev Jobs", "message-2:Architect Jobs"), appliedLabels);
	}

	@Test
	void processUnreadEmailsDoesNothingWhenTheInboxHasNoUnreadMessages() throws Exception {
		final EmailTriageSvc service = new EmailTriageSvc(
				List::of,
				email -> {
					throw new AssertionError("A summary should not be requested");
				},
				(email, summary) -> {
					throw new AssertionError("A tag should not be requested");
				},
				(messageId, labelName) -> {
					throw new AssertionError("A label should not be applied");
				});

		assertEquals(List.of(), service.processUnreadEmails());
	}

	@Test
	void processUnreadEmailsIgnoresEmailsWithoutAnAllowedLabel() throws Exception {
		final EmailMessage email = new EmailMessage("message-1", "Receipt", "shop@example.com", "Thank you for your order.");
		final EmailTriageSvc service = new EmailTriageSvc(
				() -> List.of(email),
				ignored -> "Purchase receipt",
				(ignored, summary) -> Optional.empty(),
				(messageId, labelName) -> {
					throw new AssertionError("A label should not be applied");
				});

		assertEquals(List.of(), service.processUnreadEmails());
	}
}
