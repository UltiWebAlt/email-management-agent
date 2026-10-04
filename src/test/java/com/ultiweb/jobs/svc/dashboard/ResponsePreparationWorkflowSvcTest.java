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
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseCandidate;
import com.ultiweb.jobs.svc.persistence.ResponseSelectionChange;
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
	@Mock private DeepInfraEmailReplyGenerator generator;
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
