package com.ultiweb.jobs.svc.email;

public record FollowUpEmail(String recipient, String threadId, long positionId, String parentMessageId, String subject,
		String body) {
}
