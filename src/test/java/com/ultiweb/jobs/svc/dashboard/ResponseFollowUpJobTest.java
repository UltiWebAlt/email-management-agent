package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResponseFollowUpJobTest {
	@Mock private ResponseFollowUpSvc followUpSvc;

	@Test
	void delegatesScheduledCheckToService() {
		// given
		final var job = new ResponseFollowUpJob(followUpSvc);

		// when
		job.checkResponses();

		// then
		verify(followUpSvc).checkForResponsesAndSendDueFollowUps();
	}

	@Test
	void containsServiceFailureSoTheSchedulerCanRetryLater() {
		// given
		final var job = new ResponseFollowUpJob(followUpSvc);
		doThrow(new IllegalStateException("database unavailable"))
				.when(followUpSvc).checkForResponsesAndSendDueFollowUps();

		// when / then
		assertDoesNotThrow(job::checkResponses);
	}
}
