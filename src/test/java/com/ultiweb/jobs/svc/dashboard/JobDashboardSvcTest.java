package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.persistence.JobDashboardDetailsRow;
import com.ultiweb.jobs.svc.persistence.JobDashboardMetricsRow;
import com.ultiweb.jobs.svc.persistence.JobDashboardRepository;
import com.ultiweb.jobs.svc.persistence.JobDashboardRow;
import com.ultiweb.jobs.svc.persistence.JobSortOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobDashboardSvcTest {
	private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

	@Mock private JobDashboardRepository repository;

	@Test
	void buildsDashboardFromRepositoryMetricsAndSearchResults() {
		// given
		final var service = new JobDashboardSvc(repository, Clock.fixed(NOW, ZoneOffset.UTC));
		when(repository.metrics(Instant.parse("2026-09-21T12:00:00Z")))
				.thenReturn(new JobDashboardMetricsRow(4, 2, 3, 1, NOW.minusSeconds(60)));
		when(repository.countJobs("cloud architect")).thenReturn(1L);
		when(repository.findJobs("cloud architect", JobSortOrder.NEWEST, 0, 20)).thenReturn(List.of(new JobDashboardRow(
				7, "Cloud architect", "Example", "Pat", "pat@example.com", "Remote", true,
				NOW.minusSeconds(120), NOW.minusSeconds(60), "A cloud platform role.", true,
				"SAVED_FOR_REVIEW", "Hello recruiter")));

		// when
		final DashboardSnapshot result = service.dashboard("  cloud\narchitect  ", 0, 20);

		// then
		assertEquals(NOW, result.generatedAt());
		assertEquals("cloud architect", result.query());
		assertEquals(4, result.metrics().totalJobs());
		assertEquals(1, result.totalResults());
		assertEquals(1, result.totalPages());
		assertEquals(1, result.jobs().size());
		assertEquals("Cloud architect", result.jobs().getFirst().title());
		assertEquals("Hello recruiter", result.jobs().getFirst().responseContent());
	}

	@Test
	void loadsCompleteDetailsAndReportsUnknownJobs() {
		// given
		final var service = new JobDashboardSvc(repository, Clock.fixed(NOW, ZoneOffset.UTC));
		when(repository.findById(7)).thenReturn(Optional.of(new JobDashboardDetailsRow(
				7, "Enterprise architect", null, "Pat", "pat@example.com", null, null,
				NOW, NOW, "Summary", "Email body", "<p>Email</p>", null, null, "owner@example.com",
				"Architect opening", "Pat <pat@example.com>", 2, "DRAFT_CREATED", "Reply body", "draft-1")));
		when(repository.findById(99)).thenReturn(Optional.empty());

		// when
		final DashboardJobDetails details = service.details(7);

		// then
		assertEquals("Email body", details.description());
		assertEquals(2, details.responseCount());
		assertEquals("Reply body", details.responseContent());
		assertThrows(DashboardJobNotFoundException.class, () -> service.details(99));
		verify(repository).findById(7);
	}
}
