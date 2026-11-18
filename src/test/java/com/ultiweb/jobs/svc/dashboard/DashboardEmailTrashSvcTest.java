package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import com.ultiweb.jobs.svc.persistence.JobDashboardRepository;
import com.ultiweb.jobs.svc.persistence.JobDashboardRepository.GmailSource;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardEmailTrashSvcTest {
	@Mock private JobDashboardRepository repository;
	@Mock private GmailMailboxSvc mailbox;
	@Mock private DashboardEventStream eventStream;

	@Test
	void movesTheSourceToTrashDismissesTheJobReleasesTheReservationAndNotifies() throws Exception {
		// given
		final var service = service();
		when(repository.findGmailSource(7)).thenReturn(Optional.of(new GmailSource("owner@example.com", "message-7")));
		when(repository.reserveEmailTrash(7)).thenReturn(true);

		// when
		final boolean result = service.moveToTrash(7);

		// then
		assertTrue(result);
		final InOrder ordered = inOrder(repository, mailbox, eventStream);
		ordered.verify(repository).findGmailSource(7);
		ordered.verify(repository).reserveEmailTrash(7);
		ordered.verify(mailbox).moveToTrash("owner@example.com", "message-7");
		ordered.verify(repository).dismiss(7);
		ordered.verify(repository).releaseEmailTrash(7);
		ordered.verify(eventStream).opportunityRemoved();
	}

	@Test
	void reportsMissingSourceWithoutReservingOrCallingGmail() {
		// given
		final var service = service();
		when(repository.findGmailSource(99)).thenReturn(Optional.empty());

		// when / then
		assertThrows(DashboardJobNotFoundException.class, () -> service.moveToTrash(99));
		verify(repository, never()).reserveEmailTrash(99);
		verifyNoInteractions(mailbox, eventStream);
	}

	@Test
	void returnsFalseWhenTheEmailCannotBeReserved() throws Exception {
		// given
		final var service = service();
		when(repository.findGmailSource(7)).thenReturn(Optional.of(new GmailSource("owner@example.com", "message-7")));
		when(repository.reserveEmailTrash(7)).thenReturn(false);

		// when
		final boolean result = service.moveToTrash(7);

		// then
		assertFalse(result);
		verifyNoInteractions(mailbox, eventStream);
		verify(repository, never()).dismiss(7);
		verify(repository, never()).releaseEmailTrash(7);
	}

	@Test
	void releasesReservationAfterGmailFailureAndDoesNotDismissOrPublishEvent() throws Exception {
		// given
		final var service = service();
		when(repository.findGmailSource(7)).thenReturn(Optional.of(new GmailSource("owner@example.com", "message-7")));
		when(repository.reserveEmailTrash(7)).thenReturn(true);
		doThrow(new IOException("Gmail unavailable"))
				.when(mailbox).moveToTrash("owner@example.com", "message-7");

		// when / then
		assertThrows(IOException.class, () -> service.moveToTrash(7));
		verify(repository).releaseEmailTrash(7);
		verify(repository, never()).dismiss(7);
		verifyNoInteractions(eventStream);
	}

	@Test
	void releasesReservationAfterDismissFailureAndDoesNotPublishEvent() throws Exception {
		// given
		final var service = service();
		when(repository.findGmailSource(7)).thenReturn(Optional.of(new GmailSource("owner@example.com", "message-7")));
		when(repository.reserveEmailTrash(7)).thenReturn(true);
		doThrow(new IllegalStateException("database unavailable"))
				.when(repository).dismiss(7);

		// when / then
		assertThrows(IllegalStateException.class, () -> service.moveToTrash(7));
		verify(mailbox).moveToTrash("owner@example.com", "message-7");
		verify(repository).releaseEmailTrash(7);
		verifyNoInteractions(eventStream);
	}

	private DashboardEmailTrashSvc service() {
		return new DashboardEmailTrashSvc(repository, mailbox, eventStream);
	}
}
