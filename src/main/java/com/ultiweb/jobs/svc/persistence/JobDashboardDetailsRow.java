package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;

public record JobDashboardDetailsRow(
		long id,
		String title,
		String company,
		String recruiterName,
		String recruiterEmail,
		String location,
		Boolean remote,
		Instant receivedAt,
		Instant savedAt,
		String summary,
		String description,
		String htmlBody,
		String requirements,
		String salaryRange,
		String sourceAccount,
		String sourceSubject,
		String sourceSender,
		long responseCount,
		String responseStatus,
		String responseContent,
		String gmailDraftId) {
}
