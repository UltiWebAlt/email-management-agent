package com.ultiweb.jobs.svc.dashboard;

import java.time.Instant;
import java.util.List;

public record DashboardSnapshot(
		Instant generatedAt,
		String query,
		int page,
		int pageSize,
		long totalResults,
		long totalPages,
		boolean emailImportEnabled,
		DashboardMetrics metrics,
		List<DashboardJob> jobs) {
	public DashboardSnapshot {
		jobs = List.copyOf(jobs);
	}
}
