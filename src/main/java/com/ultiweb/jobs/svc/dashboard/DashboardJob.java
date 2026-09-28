package com.ultiweb.jobs.svc.dashboard;

import java.time.Instant;

public record DashboardJob(
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
		String responseStatus) {
}
