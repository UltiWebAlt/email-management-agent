package com.ultiweb.jobs.svc.email;

import java.time.Instant;

/**
 * Gmail message with normalized text for inference and optional sanitized HTML for dashboard display.
 */
public record EmailMessage(
		String account, String id, String subject, String from, String body, Instant receivedAt, String htmlBody) {
	public EmailMessage(final String account, final String id, final String subject, final String from,
			final String body, final Instant receivedAt) {
		this(account, id, subject, from, body, receivedAt, null);
	}

	public EmailMessage(final String account, final String id, final String subject, final String from, final String body) {
		this(account, id, subject, from, body, null, null);
	}
}
