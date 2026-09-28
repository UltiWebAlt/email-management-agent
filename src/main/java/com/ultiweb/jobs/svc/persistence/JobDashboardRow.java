package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;

public record JobDashboardRow(
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
		boolean selectedForResponse,
		String responseStatus,
		String responseContent) {
}
