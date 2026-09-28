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
		String htmlBody,
		String title,
		String company,
		String location,
		Boolean remote,
		String salaryRange,
		String requirements,
		String recruiterEmail,
		String recruiterName,
		Instant createdAt) {
}
