package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailTriageSvcTest {
	@Mock private EmailReader emailReader;
	@Mock private EmailSummarySvc emailSummarySvc;
	@Mock private EmailTagSvc emailTagSvc;
	@Mock private EmailLabelWriter emailLabelWriter;
	@InjectMocks private EmailTriageSvc service;

	@Test
	void routesIdenticalMessageIdsToTheirOwningAccounts() throws Exception {
		// given
		final EmailMessage first = new EmailMessage("first@example.com", "same-id", "Invoice", "billing@example.com", "Due Friday.");
		final EmailMessage second = new EmailMessage("second@example.com", "same-id", "Standup", "team@example.com", "Tomorrow.");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(first, second));
		when(emailSummarySvc.summarize(first)).thenReturn("Payment due");
		when(emailSummarySvc.summarize(second)).thenReturn("Meeting tomorrow");
		when(emailTagSvc.suggestTag(first, "Payment due")).thenReturn(Optional.of("Dev Jobs"));
		when(emailTagSvc.suggestTag(second, "Meeting tomorrow")).thenReturn(Optional.of("Architect Jobs"));

		// when
		final List<EmailTriageResult> results = service.processUnreadEmails();

		// then
		assertEquals(List.of(
				new EmailTriageResult("first@example.com", "same-id", "Payment due", "Dev Jobs"),
				new EmailTriageResult("second@example.com", "same-id", "Meeting tomorrow", "Architect Jobs")), results);
		verify(emailLabelWriter).applyLabel("first@example.com", "same-id", "Dev Jobs");
		verify(emailLabelWriter).applyLabel("second@example.com", "same-id", "Architect Jobs");
		verifyNoMoreInteractions(emailLabelWriter);
	}

	@Test
	void processUnreadEmailsDoesNothingWhenTheInboxHasNoUnreadMessages() throws Exception {
		// given
		when(emailReader.readUnreadEmails()).thenReturn(List.of());

		// when
		final List<EmailTriageResult> results = service.processUnreadEmails();

		// then
		assertEquals(List.of(), results);
		verifyNoInteractions(emailSummarySvc, emailTagSvc, emailLabelWriter);
	}

	@Test
	void processUnreadEmailsIgnoresEmailsWithoutAnAllowedLabel() throws Exception {
		// given
		final EmailMessage email = new EmailMessage("first@example.com", "id", "Receipt", "shop@example.com", "Thank you.");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(email));
		when(emailSummarySvc.summarize(email)).thenReturn("Purchase receipt");
		when(emailTagSvc.suggestTag(email, "Purchase receipt")).thenReturn(Optional.empty());

		// when
		final List<EmailTriageResult> results = service.processUnreadEmails();

		// then
		assertEquals(List.of(), results);
		verifyNoInteractions(emailLabelWriter);
	}
}
