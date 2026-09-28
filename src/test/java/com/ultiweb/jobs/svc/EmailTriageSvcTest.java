package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailLabelWriter;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.EmailReader;
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
	void inferenceRunsConcurrentlyOnVirtualThreadsAndDuplicateMessagesRunOnce() throws Exception {
		// given
		final var first = new EmailMessage("a", "1", "", "", "");
		final var second = new EmailMessage("a", "2", "", "", "");
		final var started = new java.util.concurrent.CountDownLatch(2);
		when(emailReader.readUnreadEmails()).thenReturn(List.of(first, second, first));
		when(emailSummarySvc.summarize(any())).thenAnswer(invocation -> {
			org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isVirtual());
			started.countDown();
			org.junit.jupiter.api.Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
			return "A receipt";
		});
		when(emailTagSvc.suggestTag("A receipt")).thenReturn(Optional.of(EmailTag.RECEIPTS));

		// when
		final var results = service.processUnreadEmails();

		// then
		assertEquals(2, results.size());
		verify(emailSummarySvc).summarize(first);
		verify(emailSummarySvc).summarize(second);
		verify(emailLabelWriter).applyLabel("a", "1", "Receipts");
		verify(emailLabelWriter).applyLabel("a", "2", "Receipts");
	}

	@Test
	void routesIdenticalMessageIdsToTheirOwningAccounts() throws Exception {
		// given
		final EmailMessage first = new EmailMessage("first@example.com", "same-id", "Invoice", "billing@example.com", "Due Friday.");
		final EmailMessage second = new EmailMessage("second@example.com", "same-id", "Standup", "team@example.com", "Tomorrow.");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(first, second));
		when(emailSummarySvc.summarize(first)).thenReturn("Payment due");
		when(emailSummarySvc.summarize(second)).thenReturn("Meeting tomorrow");
		when(emailTagSvc.suggestTag("Payment due")).thenReturn(Optional.of(EmailTag.DEV_JOBS));
		when(emailTagSvc.suggestTag("Meeting tomorrow")).thenReturn(Optional.of(EmailTag.ARCHITECT_JOBS));

		// when
		final List<EmailTriageResult> results = service.processUnreadEmails();

		// then
		assertEquals(List.of(
				new EmailTriageResult("first@example.com", "same-id", "Payment due", "Dev_Jobs"),
				new EmailTriageResult("second@example.com", "same-id", "Meeting tomorrow", "Architect_Jobs")), results);
		verify(emailLabelWriter).applyLabel("first@example.com", "same-id", "Dev_Jobs");
		verify(emailLabelWriter).applyLabel("second@example.com", "same-id", "Architect_Jobs");
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
		when(emailTagSvc.suggestTag("Purchase receipt")).thenReturn(Optional.empty());

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
		when(emailTagSvc.suggestTag("Job opportunity")).thenReturn(Optional.of(EmailTag.DEV_JOBS));
		when(emailTagSvc.suggestTag("Purchase receipt")).thenReturn(Optional.empty());

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> secondPoll = service.processUnreadEmails();

		// then
		assertEquals(1, firstPoll.size());
		assertEquals(List.of(), secondPoll);
		verify(emailSummarySvc).summarize(matched);
		verify(emailSummarySvc).summarize(unmatched);
		verify(emailTagSvc).suggestTag("Job opportunity");
		verify(emailTagSvc).suggestTag("Purchase receipt");
		verify(emailLabelWriter).applyLabel("first@example.com", "1", "Dev_Jobs");
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
		when(emailTagSvc.suggestTag("Job opportunity")).thenReturn(Optional.of(EmailTag.DEV_JOBS));
		when(emailTagSvc.suggestTag("Today's news")).thenReturn(Optional.of(EmailTag.GENERAL_NEWS_PUBLICATIONS));

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> retryPoll = service.processUnreadEmails();

		// then
		assertEquals("second@example.com", firstPoll.getFirst().account());
		assertEquals("first@example.com", retryPoll.getFirst().account());
		verify(emailSummarySvc, times(2)).summarize(failing);
		verify(emailSummarySvc).summarize(healthy);
		verify(emailLabelWriter).applyLabel("first@example.com", "1", "Dev_Jobs");
		verify(emailLabelWriter).applyLabel("second@example.com", "1", "General_News_Publications");
	}

	@Test
	void failedLabelWritesAreRetriedInsteadOfMarkedProcessed() throws Exception {
		// given
		final EmailMessage email = new EmailMessage("first@example.com", "1", "Job", "jobs@example.com", "Job posting");
		when(emailReader.readUnreadEmails()).thenReturn(List.of(email));
		when(emailSummarySvc.summarize(email)).thenReturn("Job opportunity");
		when(emailTagSvc.suggestTag("Job opportunity")).thenReturn(Optional.of(EmailTag.DEV_JOBS));
		doThrow(new java.io.IOException("Gmail unavailable")).doNothing()
				.when(emailLabelWriter).applyLabel("first@example.com", "1", "Dev_Jobs");

		// when
		final List<EmailTriageResult> firstPoll = service.processUnreadEmails();
		final List<EmailTriageResult> retryPoll = service.processUnreadEmails();

		// then
		assertEquals(List.of(), firstPoll);
		assertEquals(1, retryPoll.size());
		verify(emailLabelWriter, times(2)).applyLabel("first@example.com", "1", "Dev_Jobs");
	}
}
