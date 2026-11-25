package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.email.GmailDraftSvc;
import com.ultiweb.jobs.svc.email.GmailSentMessage;
import com.ultiweb.jobs.svc.email.EmailWorkflowLabels;
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseCandidate;
import com.ultiweb.jobs.svc.persistence.ResponseSendCandidate;
import com.ultiweb.jobs.svc.persistence.ResponseSelectionChange;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ResponsePreparationWorkflowSvcTest {
	private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
	@Mock private JobResponseRepository repository;
	@Mock private EmailReplyGenerator generator;
	@Mock private GmailDraftSvc drafts;
	@Mock private ApplicationEventPublisher events;
	@Mock private Executor executor;

	@BeforeEach
	void startTransactionSynchronization() {
		TransactionSynchronizationManager.initSynchronization();
	}

	@AfterEach
	void clearTransactionSynchronization() {
		TransactionSynchronizationManager.clearSynchronization();
	}

	@Test
	void queuesResponsesOnlyAfterCommit() {
		// given
		final var changes = List.of(new DashboardSelectionChange(7, true));
		when(repository.updateSelections(List.of(new ResponseSelectionChange(7, true)))).thenReturn(1);
		when(repository.findSelectedWithoutResponse()).thenReturn(List.of(7L));
		final var service = service(true, executor);

		// when
		final ResponseSelectionResult result = service.save(changes);

		// then
		assertEquals(new ResponseSelectionResult(1, 1, "development"), result);
		verifyNoInteractions(executor, generator, drafts);
		commit();
		verify(executor).execute(any(Runnable.class));
	}

	@Test
	void rejectsInvalidAndDuplicatePositionIdsBeforeUpdatingSelections() {
		// given
		final var service = service(true, executor);
		final var invalid = List.of(new DashboardSelectionChange(0, true));
		final var duplicate = List.of(new DashboardSelectionChange(7, true), new DashboardSelectionChange(7, false));

		// when / then
		assertThrows(ResponseSelectionException.class, () -> service.save(invalid));
		assertThrows(ResponseSelectionException.class, () -> service.save(duplicate));
		verifyNoInteractions(repository, executor);
	}

	@Test
	void rejectsMissingPositionsWithoutSchedulingResponses() {
		// given
		final var service = service(true, executor);
		final var changes = List.of(new DashboardSelectionChange(7, true));
		when(repository.updateSelections(anyList())).thenReturn(0);

		// when / then
		assertThrows(ResponseSelectionException.class, () -> service.save(changes));
		verify(repository, never()).findSelectedWithoutResponse();
		verifyNoInteractions(executor);
	}

	@Test
	void savesDevelopmentResponsesWithoutCreatingGmailDrafts() {
		// given
		final var candidate = candidate("Architect opening");
		prepare(candidate);
		when(generator.generate(candidate)).thenReturn("Interested in the role.");
		when(repository.isSelected(7)).thenReturn(true);
		final var service = service(true, Runnable::run);

		// when
		service.save(List.of());
		commit();

		// then
		verify(repository).saveGeneratedResponse(7, "Interested in the role.", "SAVED_FOR_REVIEW", NOW);
		verify(events).publishEvent(new DashboardResponseUpdatedEvent(7));
		verifyNoInteractions(drafts);
	}

	@Test
	void createsAndRecordsProductionDrafts() throws Exception {
		// given
		final var candidate = candidate("Architect\nopening");
		prepare(candidate);
		when(generator.generate(candidate)).thenReturn("Reply");
		when(repository.isSelected(7)).thenReturn(true);
		when(drafts.createDraft("owner@example.com", "pat@example.com", "Re: Architect opening", "Reply"))
				.thenReturn("draft-7");
		final var service = service(false, Runnable::run);

		// when
		assertEquals("gmail-drafts", service.save(List.of()).mode());
		commit();

		// then
		verify(repository).saveGeneratedResponse(7, "Reply", "GENERATED", NOW);
		verify(repository).saveDraftId(7, "draft-7", NOW);
		verify(events).publishEvent(new DashboardResponseUpdatedEvent(7));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "Re: Architect opening"})
	void handlesMissingSubjectsAndAvoidsDuplicateReplyPrefixes(final String subject) throws Exception {
		// given
		final var candidate = candidate(subject);
		prepare(candidate);
		when(generator.generate(candidate)).thenReturn("Reply");
		when(repository.isSelected(7)).thenReturn(true);
		final var service = service(false, Runnable::run);

		// when
		service.save(List.of());
		commit();

		// then
		final String expected = subject == null || subject.isBlank() ? "Re: Architect job opportunity" : subject;
		verify(drafts).createDraft("owner@example.com", "pat@example.com", expected, "Reply");
	}

	@Test
	void skipsResponsesAlreadyClaimedByAnotherWorker() {
		// given
		when(repository.findSelectedWithoutResponse()).thenReturn(List.of(7L));
		when(repository.claimResponse(7, NOW)).thenReturn(Optional.empty());
		final var service = service(false, Runnable::run);

		// when
		service.save(List.of());
		commit();

		// then
		verifyNoInteractions(generator, drafts, events);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void stopsWhenTheUserDeselectsBeforeOrAfterSavingTheGeneratedResponse(final boolean deselectAfterSave) {
		// given
		final var candidate = candidate("Architect opening");
		prepare(candidate);
		when(generator.generate(candidate)).thenReturn("Reply");
		when(repository.isSelected(7)).thenReturn(deselectAfterSave, false);
		final var service = service(false, Runnable::run);

		// when
		service.save(List.of());
		commit();

		// then
		verify(repository).markResponseFailed(7, NOW);
		if (deselectAfterSave) {
			verify(repository).saveGeneratedResponse(7, "Reply", "GENERATED", NOW);
		} else {
			verify(repository, never()).saveGeneratedResponse(eq(7L), anyString(), anyString(), any());
		}
		verifyNoInteractions(drafts);
	}

	@Test
	void recordsGenerationFailuresAndNotifiesTheDashboard() {
		// given
		final var candidate = candidate("Architect opening");
		prepare(candidate);
		when(generator.generate(candidate)).thenThrow(new IllegalStateException("Unavailable"));
		final var service = service(false, Runnable::run);

		// when
		service.save(List.of());
		commit();

		// then
		verify(repository).markResponseFailed(7, NOW);
		verify(events).publishEvent(new DashboardResponseUpdatedEvent(7));
		verifyNoInteractions(drafts);
	}

	private void prepare(final ResponseCandidate candidate) {
		when(repository.findSelectedWithoutResponse()).thenReturn(List.of(7L));
		when(repository.claimResponse(7, NOW)).thenReturn(Optional.of(candidate));
	}

	@Test
	void preparesOneResponseOnlyAfterCommit() {
		// given
		when(repository.updateSelections(List.of(new ResponseSelectionChange(7, true)))).thenReturn(1);
		when(repository.findSelectedWithoutResponse()).thenReturn(List.of(7L));
		final var service = service(true, executor);

		// when
		assertEquals(new ResponseSelectionResult(1, 1, "development"), service.prepare(7));

		// then
		verifyNoInteractions(executor);
		commit();
		verify(executor).execute(any(Runnable.class));
	}

	@Test
	void rejectsInvalidPreparationIdsBeforeAccessingTheRepository() {
		// given
		final var service = service(true, executor);

		// when / then
		assertThrows(ResponseSelectionException.class, () -> service.prepare(0));
		verifyNoInteractions(repository, executor);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void rejectsMissingOrAlreadyPreparedOpportunities(final boolean exists) {
		// given
		when(repository.updateSelections(anyList())).thenReturn(exists ? 1 : 0);
		if (exists) {
			when(repository.findSelectedWithoutResponse()).thenReturn(List.of());
		}
		final var service = service(false, executor);

		// when / then
		assertThrows(ResponseSelectionException.class, () -> service.prepare(7));
		commit();
		verifyNoInteractions(executor);
	}

	@Test
	void doesNotSendAnUnclaimedResponse() throws Exception {
		// given
		when(repository.claimResponseForSending(7, NOW)).thenReturn(Optional.empty());

		// when / then
		assertEquals(false, service(false, executor).send(7));
		verifyNoInteractions(drafts, generator, events);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "draft-7"})
	void createsMissingDraftsAndRecordsProductionSends(final String draftId) throws Exception {
		// given
		final var candidate = new ResponseSendCandidate(7, "owner@example.com", "pat@example.com",
				"Architect opening", "Reply", draftId);
		when(repository.claimResponseForSending(7, NOW)).thenReturn(Optional.of(candidate));
		if (draftId == null || draftId.isBlank()) {
			when(drafts.createDraft("owner@example.com", "pat@example.com", "Re: Architect opening", "Reply"))
					.thenReturn("draft-7");
		}
		final Instant sentAt = NOW.minusSeconds(2);
		when(drafts.sendReply("owner@example.com", "draft-7", "pat@example.com", "Re: Architect opening",
				"Reply", null, false)).thenReturn(new GmailSentMessage("draft-7", "sent-7", "thread-7", sentAt, true));

		// when / then
		assertEquals(true, service(false, executor).send(7));
		verify(repository).markResponseSent(7, "draft-7", "sent-7", "thread-7", "SENT", sentAt);
		verify(events).publishEvent(new DashboardResponseUpdatedEvent(7));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void redirectsTestSendsAndRecordsLabelFailures(final boolean labelApplied) throws Exception {
		// given
		final var candidate = new ResponseSendCandidate(7, "owner@example.com", "pat@example.com",
				"Re: Architect opening", "Reply", "draft-7");
		when(repository.claimResponseForSending(7, NOW)).thenReturn(Optional.of(candidate));
		when(drafts.sendReply("owner@example.com", "draft-7", "owner@example.com", "Re: Architect opening",
				"Reply", EmailWorkflowLabels.EMAIL_AGENT_TEST, true))
				.thenReturn(new GmailSentMessage("draft-7", "sent-7", "thread-7", null, labelApplied));
		final var service = new ResponsePreparationWorkflowSvc(repository, generator, drafts, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), false, false, events);

		// when / then
		assertEquals(true, service.send(7));
		verify(repository).markResponseSent(7, "draft-7", "sent-7", "thread-7",
				labelApplied ? "SENT" : "SENT_LABEL_FAILED", NOW);
		verify(drafts, never()).createDraft(anyString(), anyString(), anyString(), anyString());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void recordsAndPropagatesSendFailures(final boolean mimeFailure) throws Exception {
		// given
		final var candidate = new ResponseSendCandidate(7, "owner@example.com", "pat@example.com", null, "Reply", "draft-7");
		when(repository.claimResponseForSending(7, NOW)).thenReturn(Optional.of(candidate));
		final Exception failure = mimeFailure ? new javax.mail.MessagingException("Invalid MIME") : new IOException("Unavailable");
		when(drafts.sendReply("owner@example.com", "draft-7", "pat@example.com", "Re: Architect job opportunity",
				"Reply", null, false)).thenThrow(failure);

		// when / then
		final Exception thrown = assertThrows(Exception.class, () -> service(false, executor).send(7));
		assertEquals(failure, thrown);
		verify(repository).markResponseSendFailed(7, NOW);
		verify(events).publishEvent(new DashboardResponseUpdatedEvent(7));
	}

	@Test
	void directsNonproductionDraftsToTheSourceAccount() throws Exception {
		// given
		final var candidate = candidate("Architect opening");
		prepare(candidate);
		when(generator.generate(candidate)).thenReturn("Reply");
		when(repository.isSelected(7)).thenReturn(true);
		final var service = new ResponsePreparationWorkflowSvc(repository, generator, drafts, Runnable::run,
				Clock.fixed(NOW, ZoneOffset.UTC), false, false, events);

		// when
		service.save(List.of());
		commit();

		// then
		verify(drafts).createDraft("owner@example.com", "owner@example.com", "Re: Architect opening", "Reply");
	}

	private ResponsePreparationWorkflowSvc service(final boolean dev, final Executor taskExecutor) {
		return new ResponsePreparationWorkflowSvc(repository, generator, drafts, taskExecutor,
				Clock.fixed(NOW, ZoneOffset.UTC), dev, events);
	}

	private static void commit() {
		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
	}

	private static ResponseCandidate candidate(final String subject) {
		return new ResponseCandidate(7, 3, "owner@example.com", subject, "Pat", "Body", "Summary",
				"Architect", "pat@example.com", "Pat");
	}
}
