package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ultiweb.jobs.svc.persistence.ArchitectOpportunityAddedEvent;
import com.ultiweb.jobs.svc.persistence.ArchitectOpportunityUpdatedEvent;
import com.ultiweb.jobs.web.JobDashboardController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardEventStreamTest {
	@Test
	void streamsConnectionRefreshAndImportStateEventsToEverySession() throws Exception {
		// given
		final var stream = new DashboardEventStream();
		final MockMvc mvc = mvc(stream);
		final var first = mvc.perform(get("/api/dashboard/events").accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
		final var second = mvc.perform(get("/api/dashboard/events").accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(request().asyncStarted()).andReturn();

		// when
		stream.opportunityAdded(new ArchitectOpportunityAddedEvent("message-1"));
		stream.opportunityUpdated(new ArchitectOpportunityUpdatedEvent("message-1"));
		stream.responseUpdated(new DashboardResponseUpdatedEvent(7));
		stream.importStateChanged(true);
		stream.importStateChanged(false);

		// then
		final String events = first.getResponse().getContentAsString();
		assertEquals(events, second.getResponse().getContentAsString());
		assertTrue(events.contains("event:connected"));
		assertTrue(events.contains("event:opportunity-added"));
		assertTrue(events.contains("event:opportunity-updated"));
		assertTrue(events.contains("event:response-updated"));
		assertTrue(events.contains("data:{\"running\":true}"));
		assertTrue(events.contains("data:{\"running\":false}"));
	}

	@Test
	void continuesSendingToLiveSessionsWhenAnotherEmitterHasCompleted() throws Exception {
		// given
		final var stream = new DashboardEventStream();
		final var completed = stream.connect();
		completed.complete();
		final var live = mvc(stream).perform(get("/api/dashboard/events"))
				.andExpect(request().asyncStarted()).andReturn();

		// when
		stream.importStateChanged(true);
		stream.importStateChanged(false);

		// then
		assertTrue(live.getResponse().getContentAsString().contains("data:{\"running\":false}"));
	}

	private static MockMvc mvc(final DashboardEventStream stream) {
		return MockMvcBuilders.standaloneSetup(new JobDashboardController(mock(JobDashboardSvc.class),
				mock(ResponsePreparationWorkflowSvc.class), stream)).build();
	}
}
