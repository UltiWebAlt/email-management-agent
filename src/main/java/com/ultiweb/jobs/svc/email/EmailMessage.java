package com.ultiweb.jobs.svc.email;

import java.time.Instant;

/**
 * Plain-text representation of a Gmail message used by the triage workflow.
 */
public record EmailMessage(String account, String id, String subject, String from, String body, Instant receivedAt) {
	public EmailMessage(final String account, final String id, final String subject, final String from, final String body) {
		this(account, id, subject, from, body, null);
	}
}
