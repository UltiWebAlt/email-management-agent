package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;

public record ResponseFollowUpCandidate(
		long positionId,
		String sourceAccount,
		String recruiterEmail,
		String recruiterName,
		String title,
		String sourceSubject,
		String responseContent,
		String followUpContent,
		String gmailMessageId,
		String gmailThreadId,
		Instant sentAt,
		String followUpStatus,
		Instant followUpSentAt,
		String followUpMessageId,
		Instant recruiterReplyAt,
		String recruiterReplyMessageId) {
}
