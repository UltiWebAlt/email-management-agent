package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;

public record JobDashboardMetricsRow(
		long totalJobs,
		long recentJobs,
		long recruiters,
		long followUps,
		Instant lastSavedAt) {
}
