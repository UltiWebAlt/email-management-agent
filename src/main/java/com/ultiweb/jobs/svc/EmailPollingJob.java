package com.ultiweb.jobs.svc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts triage on application startup and repeats after each completed poll.
 */
public class EmailPollingJob {
	private static final Logger LOGGER = LoggerFactory.getLogger(EmailPollingJob.class);
	private final EmailTriageSvc triageService;

	public EmailPollingJob(final EmailTriageSvc triageService) {
		this.triageService = triageService;
	}

	@Scheduled(initialDelay = 0, fixedDelayString = "${gmail.polling.interval:PT5M}")
	public void poll() {
		LOGGER.info("Starting unread-email poll for the configured Gmail accounts.");
		try {
			final var results = triageService.processEmails();
			LOGGER.info("Unread-email poll finished: {} labels applied. Waiting for the next polling interval.", results.size());
		} catch (final Exception exception) {
			LOGGER.error("Unread-email poll failed; it will retry at the next polling interval.", exception);
		}
	}
}
