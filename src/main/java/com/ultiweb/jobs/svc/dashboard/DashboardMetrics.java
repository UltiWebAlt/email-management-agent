package com.ultiweb.jobs.svc.dashboard;

import java.time.Instant;

public record DashboardMetrics(
		long totalJobs,
		long recentJobs,
		long recruiters,
		long followUps,
		Instant lastSavedAt) {
}
