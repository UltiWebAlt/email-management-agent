package com.ultiweb.jobs.svc.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ResponseFollowUpJob {
	private static final Logger LOGGER = LoggerFactory.getLogger(ResponseFollowUpJob.class);
	private final ResponseFollowUpSvc followUpSvc;

	public ResponseFollowUpJob(final ResponseFollowUpSvc followUpSvc) {
		this.followUpSvc = followUpSvc;
	}

	@Scheduled(initialDelayString = "${response.follow-up.initial-delay:PT1M}",
			fixedDelayString = "${response.follow-up.interval:PT1H}")
	public void checkResponses() {
		try {
			followUpSvc.checkForResponsesAndSendDueFollowUps();
		} catch (final Exception exception) {
			LOGGER.error("Scheduled Gmail response tracking failed; it will retry next hour.", exception);
		}
	}
}
