package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobDashboardRepository {
	JobDashboardMetricsRow metrics(Instant recentSince);

	long countJobs(String query);

	List<JobDashboardRow> findJobs(String query, JobSortOrder sortOrder, int offset, int limit);

	Optional<JobDashboardDetailsRow> findById(long id);
}
