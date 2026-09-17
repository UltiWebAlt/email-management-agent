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

	@Test
	void doesNotRepeatInferenceForLabeledOrUnmatchedEmailsDuringLaterPolls() throws Exception {
		// given
		final EmailMessage matched = new EmailMessage("first@example.com", "1", "Job", "jobs@example.com", "Job posting");
		final EmailMessage unmatched = new EmailMessage("first@example.com", "2", "Receipt", "shop@example.com", "Thanks");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(matched, unmatched));
		when(emailSummarySvc.summarize(matched)).thenReturn("Job opportunity");
		when(emailSummarySvc.summarize(unmatched)).thenReturn("Purchase receipt");
		when(emailTagSvc.suggestTag(matched, "Job opportunity")).thenReturn(Optional.of("Dev Jobs"));
		when(emailTagSvc.suggestTag(unmatched, "Purchase receipt")).thenReturn(Optional.empty());

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> secondPoll = service.processUnreadEmails();

		// then
		assertEquals(1, firstPoll.size());
		assertEquals(List.of(), secondPoll);
		verify(emailSummarySvc).summarize(matched);
		verify(emailSummarySvc).summarize(unmatched);
		verify(emailTagSvc).suggestTag(matched, "Job opportunity");
		verify(emailTagSvc).suggestTag(unmatched, "Purchase receipt");
		verify(emailLabelWriter).applyLabel("first@example.com", "1", "Dev Jobs");
		verifyNoMoreInteractions(emailSummarySvc, emailTagSvc, emailLabelWriter);
	}

	@Test
	void failedInferenceDoesNotBlockOtherMessagesAndIsRetriedNextPoll() throws Exception {
		// given
		final EmailMessage failing = new EmailMessage("first@example.com", "1", "Job", "jobs@example.com", "Job posting");
		final EmailMessage healthy = new EmailMessage("second@example.com", "1", "News", "news@example.com", "News story");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(failing, healthy));
		when(emailSummarySvc.summarize(failing)).thenThrow(new IllegalStateException("Inference unavailable"))
				.thenReturn("Job opportunity");
		when(emailSummarySvc.summarize(healthy)).thenReturn("Today's news");
		when(emailTagSvc.suggestTag(failing, "Job opportunity")).thenReturn(Optional.of("Dev Jobs"));
		when(emailTagSvc.suggestTag(healthy, "Today's news")).thenReturn(Optional.of("General News Publications"));

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> retryPoll = service.processUnreadEmails();

		// then
		assertEquals("second@example.com", firstPoll.getFirst().account());
		assertEquals("first@example.com", retryPoll.getFirst().account());
		verify(emailSummarySvc, times(2)).summarize(failing);
		verify(emailSummarySvc).summarize(healthy);
		verify(emailLabelWriter).applyLabel("first@example.com", "1", "Dev Jobs");
		verify(emailLabelWriter).applyLabel("second@example.com", "1", "General News Publications");
	}

	@Test
	void failedLabelWritesAreRetriedInsteadOfMarkedProcessed() throws Exception {
		// given
		final EmailMessage email = new EmailMessage("first@example.com", "1", "Job", "jobs@example.com", "Job posting");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(email));
		when(emailSummarySvc.summarize(email)).thenReturn("Job opportunity");
		when(emailTagSvc.suggestTag(email, "Job opportunity")).thenReturn(Optional.of("Dev Jobs"));
		doThrow(new java.io.IOException("Gmail unavailable")).doNothing()
				.when(emailLabelWriter).applyLabel("first@example.com", "1", "Dev Jobs");

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> retryPoll = service.processUnreadEmails();

		// then
		assertEquals(List.of(), firstPoll);
		assertEquals(1, retryPoll.size());
		verify(emailLabelWriter, times(2)).applyLabel("first@example.com", "1", "Dev Jobs");
	}
}
