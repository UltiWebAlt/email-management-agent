package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;

public record ArchitectJobRecord(
		String sourceAccount,
		String sourceMessageId,
		String sourceSubject,
		String sourceSender,
		Instant sourceReceivedAt,
		String summary,
		String description,
		String title,
		String recruiterEmail,
		String recruiterName,
		Instant createdAt) {
}
