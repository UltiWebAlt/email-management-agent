package com.ultiweb.jobs.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ultiweb.jobs.svc.dashboard.DashboardEmailTrashSvc;
import com.ultiweb.jobs.svc.dashboard.DashboardJobNotFoundException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DashboardEmailTrashControllerTest {
	@Mock private DashboardEmailTrashSvc trashSvc;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new DashboardEmailTrashController(trashSvc))
				.setControllerAdvice(new DashboardExceptionHandler())
				.build();
	}

	@Test
	void returnsNoContentAfterMovingEmailToTrash() throws Exception {
		// given
		when(trashSvc.moveToTrash(7)).thenReturn(true);

		// when / then
		mockMvc.perform(delete("/api/dashboard/jobs/7/email"))
				.andExpect(status().isNoContent());
		verify(trashSvc).moveToTrash(7);
	}

	@Test
	void returnsConflictWhenTheEmailCannotBeReserved() throws Exception {
		// given
		when(trashSvc.moveToTrash(7)).thenReturn(false);

		// when / then
		mockMvc.perform(delete("/api/dashboard/jobs/7/email"))
				.andExpect(status().isConflict());
	}

	@Test
	void returnsNotFoundWhenTheJobHasNoTrashingSource() throws Exception {
		// given
		when(trashSvc.moveToTrash(99)).thenThrow(new DashboardJobNotFoundException(99));

		// when / then
		mockMvc.perform(delete("/api/dashboard/jobs/99/email"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Architect job 99 was not found"));
	}

	@Test
	void returnsBadGatewayWhenGmailCannotMoveTheMessage() throws Exception {
		// given
		when(trashSvc.moveToTrash(7)).thenThrow(new IOException("Gmail unavailable"));

		// when / then
		mockMvc.perform(delete("/api/dashboard/jobs/7/email"))
				.andExpect(status().isBadGateway());
	}
}
