package com.ultiweb.jobs.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ultiweb.jobs.svc.dashboard.DashboardEventStream;
import com.ultiweb.jobs.svc.dashboard.DevelopmentArchitectEmailImportSvc;
import com.ultiweb.jobs.svc.dashboard.DevelopmentEmailImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DevelopmentEmailImportControllerTest {
	@Mock private DevelopmentArchitectEmailImportSvc importSvc;
	@Mock private DashboardEventStream eventStream;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
				new DevelopmentEmailImportController(importSvc, eventStream)).build();
	}

	@Test
	void publishesImportStartAndFinishAndReportsIdleAfterCompletion() throws Exception {
		// given
		when(importSvc.importArchitectEmails()).thenReturn(new DevelopmentEmailImportResult(1, 1, 0, 0, 0, 0));

		// when / then
		mockMvc.perform(post("/api/dashboard/dev/import-architect-emails"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imported").value(1));
		mockMvc.perform(get("/api/dashboard/dev/import-architect-emails/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.running").value(false));
		verify(eventStream).importStateChanged(true);
		verify(eventStream).importStateChanged(false);
	}
}
