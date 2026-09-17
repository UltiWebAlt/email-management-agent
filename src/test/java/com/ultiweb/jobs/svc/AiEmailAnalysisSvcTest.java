package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AiEmailAnalysisSvcTest {

	@Test
	void summarizeIncludesTheEmailContentInTheAiRequest() {
		final AtomicReference<String> userPrompt = new AtomicReference<>();
		final AiEmailSummarySvc service = new AiEmailSummarySvc((system, user) -> {
			userPrompt.set(user);
			return "A payment is due Friday.";
		});

		final String summary = service.summarize(new EmailMessage("owner@example.com", "id", "Invoice", "billing@example.com", "Pay by Friday."));

		assertEquals("A payment is due Friday.", summary);
		assertTrue(userPrompt.get().contains("Subject: Invoice"));
		assertTrue(userPrompt.get().contains("Pay by Friday."));
	}

	@Test
	void suggestTagTrimsTheModelResponse() {
		final AiEmailTagSvc service = new AiEmailTagSvc((system, user) -> " Dev Jobs \n");

		assertEquals("Dev Jobs", service.suggestTag(
				new EmailMessage("owner@example.com", "id", "Invoice", "billing@example.com", "Pay by Friday."),
				"A payment is due Friday.").orElseThrow());
	}

	@Test
	void suggestTagIgnoresAnEmailThatDoesNotMatchAnAllowedLabel() {
		final AiEmailTagSvc service = new AiEmailTagSvc((system, user) -> "NONE");

		assertTrue(service.suggestTag(
				new EmailMessage("owner@example.com", "id", "Receipt", "shop@example.com", "Thank you for your order."),
				"Purchase receipt").isEmpty());
	}
}
