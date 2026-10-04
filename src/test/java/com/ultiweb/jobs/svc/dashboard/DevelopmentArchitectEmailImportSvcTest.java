package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.JobOpportunityDetails;
import com.ultiweb.jobs.svc.ai.ArchitectJobDetailsInferenceSvc;
import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import com.ultiweb.jobs.svc.persistence.ArchitectJobPersistenceSvc;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DevelopmentArchitectEmailImportSvcTest {
	@Mock private GmailMailboxSvc mailbox;
	@Mock private EmailSummarySvc summaries;
	@Mock private EmailTagSvc tags;
	@Mock private ArchitectJobDetailsInferenceSvc inference;
	@Mock private ArchitectJobPersistenceSvc persistence;
	@InjectMocks private DevelopmentArchitectEmailImportSvc service;

	@Test
	void prioritizesManuallyTaggedEmailsDeduplicatesAndStopsAfterTenImports() throws Exception {
		// given
		final List<EmailMessage> emails = IntStream.rangeClosed(1, 11).mapToObj(i -> email("message-" + i)).toList();
		when(mailbox.readArchitectLabeledEmailsForDevelopment()).thenReturn(emails);
		when(mailbox.readRecentInboxEmailsForDevelopment()).thenReturn(List.of(emails.getFirst()));
		when(summaries.summarize(any())).thenReturn("Summary");
		when(inference.infer(any())).thenReturn(JobOpportunityDetails.empty());
		when(persistence.persist(any(), eq("Summary"), any())).thenReturn(true);

		// when
		final DevelopmentEmailImportResult result = service.importArchitectEmails();

		// then
		assertEquals(new DevelopmentEmailImportResult(10, 10, 0, 0, 0, 0), result);
		verify(summaries, never()).summarize(emails.getLast());
		verifyNoInteractions(tags);
	}

	@Test
	void enrichesIncompleteRecordsAndSkipsCompleteOnesWithoutReclassifying() throws Exception {
		// given
		final EmailMessage incomplete = email("incomplete");
		final EmailMessage complete = email("complete");
		when(mailbox.readArchitectLabeledEmailsForDevelopment()).thenReturn(List.of(incomplete, complete));
		when(mailbox.readRecentInboxEmailsForDevelopment()).thenReturn(List.of());
		when(persistence.isPersisted(any())).thenReturn(true);
		when(persistence.needsEnrichment(incomplete)).thenReturn(true);
		final JobOpportunityDetails details = JobOpportunityDetails.empty();
		when(inference.infer(incomplete)).thenReturn(details);

		// when
		final DevelopmentEmailImportResult result = service.importArchitectEmails();

		// then
		assertEquals(new DevelopmentEmailImportResult(2, 0, 1, 1, 0, 0), result);
		verify(persistence).enrichExisting(incomplete, details);
		verify(persistence, never()).enrichExisting(eq(complete), any());
		verifyNoInteractions(summaries, tags);
	}

	@Test
	void rejectsNonArchitectEmailsAndContinuesAfterAnInferenceFailure() throws Exception {
		// given
		final EmailMessage other = email("other");
		final EmailMessage architect = email("architect");
		when(mailbox.readArchitectLabeledEmailsForDevelopment()).thenReturn(List.of());
		when(mailbox.readRecentInboxEmailsForDevelopment()).thenReturn(List.of(other, architect));
		when(summaries.summarize(other)).thenReturn("Other summary");
		when(summaries.summarize(architect)).thenReturn("Architect summary");
		when(tags.suggestTag("Other summary")).thenReturn(Optional.empty());
		when(tags.suggestTag("Architect summary")).thenReturn(Optional.of(EmailTag.ARCHITECT_JOBS));
		when(inference.infer(architect)).thenThrow(new IllegalStateException("Unavailable"));
		when(persistence.persist(architect, "Architect summary", JobOpportunityDetails.empty())).thenReturn(true);

		// when
		final DevelopmentEmailImportResult result = service.importArchitectEmails();

		// then
		assertEquals(new DevelopmentEmailImportResult(2, 1, 0, 0, 1, 0), result);
		verify(persistence, never()).persist(eq(other), anyString(), any());
	}

	@Test
	void countsFailedImportsAndConcurrentDuplicatesAndContinuesProcessing() throws Exception {
		// given
		final EmailMessage failed = email("failed");
		final EmailMessage duplicate = email("duplicate");
		when(mailbox.readArchitectLabeledEmailsForDevelopment()).thenReturn(List.of(failed, duplicate));
		when(mailbox.readRecentInboxEmailsForDevelopment()).thenReturn(List.of());
		when(summaries.summarize(failed)).thenThrow(new IllegalStateException("Unavailable"));
		when(summaries.summarize(duplicate)).thenReturn("Summary");
		when(inference.infer(duplicate)).thenReturn(JobOpportunityDetails.empty());
		when(persistence.persist(duplicate, "Summary", JobOpportunityDetails.empty())).thenReturn(false);

		// when
		final DevelopmentEmailImportResult result = service.importArchitectEmails();

		// then
		assertEquals(new DevelopmentEmailImportResult(2, 0, 0, 1, 0, 1), result);
	}

	@Test
	void returnsEmptyCountsForAnEmptyMailbox() throws Exception {
		// given
		when(mailbox.readArchitectLabeledEmailsForDevelopment()).thenReturn(List.of());
		when(mailbox.readRecentInboxEmailsForDevelopment()).thenReturn(List.of());

		// when
		final DevelopmentEmailImportResult result = service.importArchitectEmails();

		// then
		assertEquals(new DevelopmentEmailImportResult(0, 0, 0, 0, 0, 0), result);
		verifyNoInteractions(summaries, tags, inference, persistence);
	}

	private static EmailMessage email(final String id) {
		return new EmailMessage("owner@example.com", id, "Architect opening", "pat@example.com", "Body");
	}
}
