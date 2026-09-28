package com.ultiweb.jobs.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ultiweb.jobs.svc.dashboard.DashboardJob;
import com.ultiweb.jobs.svc.dashboard.DashboardJobNotFoundException;
import com.ultiweb.jobs.svc.dashboard.DashboardEventStream;
import com.ultiweb.jobs.svc.dashboard.DashboardMetrics;
import com.ultiweb.jobs.svc.dashboard.DashboardSnapshot;
import com.ultiweb.jobs.svc.dashboard.JobDashboardSvc;
import com.ultiweb.jobs.svc.dashboard.ResponsePreparationWorkflowSvc;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class JobDashboardControllerTest {
	@Mock private JobDashboardSvc dashboardSvc;
	@Mock private ResponsePreparationWorkflowSvc responseWorkflowSvc;
	@Mock private DashboardEventStream eventStream;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new JobDashboardController(dashboardSvc, responseWorkflowSvc, eventStream))
				.setControllerAdvice(new DashboardExceptionHandler())
				.build();
	}

	@Test
	void returnsDashboardAsJson() throws Exception {
		// given
		final Instant now = Instant.parse("2026-09-28T12:00:00Z");
		when(dashboardSvc.dashboard("cloud", 0, 20)).thenReturn(new DashboardSnapshot(now, "cloud", 0, 20, 1, 1, false,
				new DashboardMetrics(1, 1, 1, 0, now),
				List.of(new DashboardJob(7, "Cloud architect", "Example", "Pat", "pat@example.com",
						"Remote", true, now, now, "Summary", false, null))));

		// when / then
		mockMvc.perform(get("/api/dashboard").queryParam("query", "cloud"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.metrics.totalJobs").value(1))
				.andExpect(jsonPath("$.jobs[0].title").value("Cloud architect"));
	}

	@Test
	void returnsStandardErrorEnvelopeForUnknownJob() throws Exception {
		// given
		when(dashboardSvc.details(99)).thenThrow(new DashboardJobNotFoundException(99));

		// when / then
		mockMvc.perform(get("/api/dashboard/jobs/99"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Architect job 99 was not found"));
	}
}
