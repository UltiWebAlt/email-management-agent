package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.persistence.JobDashboardDetailsRow;
import com.ultiweb.jobs.svc.persistence.JobDashboardRepository;
import com.ultiweb.jobs.svc.persistence.JobDashboardRow;
import com.ultiweb.jobs.svc.persistence.JobSortOrder;
import com.ultiweb.jobs.svc.HiringCompanyExtractor;
import com.ultiweb.jobs.svc.email.EmailHtmlDisplayRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class JobDashboardSvc {
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_QUERY_LENGTH = 200;
	private static final Duration RECENT_WINDOW = Duration.ofDays(7);

	private final JobDashboardRepository repository;
	private final Clock clock;
	private final boolean emailImportEnabled;

	@Autowired
	public JobDashboardSvc(final JobDashboardRepository repository,
			@Value("${dashboard.email-import-enabled:false}") final boolean emailImportEnabled) {
		this(repository, Clock.systemUTC(), emailImportEnabled);
	}

	JobDashboardSvc(final JobDashboardRepository repository, final Clock clock) {
		this(repository, clock, false);
	}

	JobDashboardSvc(final JobDashboardRepository repository, final Clock clock, final boolean emailImportEnabled) {
		this.repository = repository;
		this.clock = clock;
		this.emailImportEnabled = emailImportEnabled;
	}

	public DashboardSnapshot dashboard(final String query, final int requestedPage, final int requestedPageSize) {
		return dashboard(query, requestedPage, requestedPageSize, "newest");
	}

	public DashboardSnapshot dashboard(final String query, final int requestedPage, final int requestedPageSize,
			final String requestedSortOrder) {
		final Instant now = clock.instant();
		final String normalizedQuery = normalizeQuery(query);
		final JobSortOrder sortOrder = JobSortOrder.fromValue(requestedSortOrder);
		final int pageSize = Math.clamp(requestedPageSize, 1, MAX_PAGE_SIZE);
		final long totalResults = repository.countJobs(normalizedQuery);
		final long totalPages = Math.max(1, (totalResults + pageSize - 1) / pageSize);
		final int page = Math.toIntExact(Math.clamp(requestedPage, 0L, totalPages - 1));
		final var metricsRow = repository.metrics(now.minus(RECENT_WINDOW));
		return new DashboardSnapshot(
				now,
				normalizedQuery,
				page,
				pageSize,
				totalResults,
				totalPages,
				emailImportEnabled,
				new DashboardMetrics(metricsRow.totalJobs(), metricsRow.recentJobs(), metricsRow.recruiters(),
						metricsRow.followUps(), metricsRow.lastSavedAt()),
				repository.findJobs(normalizedQuery, sortOrder, page * pageSize, pageSize).stream()
						.map(JobDashboardSvc::job).toList());
	}

	public DashboardJobDetails details(final long id) {
		return repository.findById(id)
				.map(JobDashboardSvc::details)
				.orElseThrow(() -> new DashboardJobNotFoundException(id));
	}

	private static String normalizeQuery(final String query) {
		if (query == null || query.isBlank()) {
			return "";
		}
		final String normalized = query.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return normalized.length() <= MAX_QUERY_LENGTH ? normalized : normalized.substring(0, MAX_QUERY_LENGTH);
	}

	private static DashboardJob job(final JobDashboardRow row) {
		return new DashboardJob(row.id(), row.title(), firstNonBlank(row.company(), HiringCompanyExtractor.extract(row.title())),
				row.recruiterName(), row.recruiterEmail(),
				row.location(), row.remote(), row.receivedAt(), row.savedAt(), row.summary(),
				row.selectedForResponse(), row.responseStatus(), row.responseContent());
	}

	private static DashboardJobDetails details(final JobDashboardDetailsRow row) {
		return new DashboardJobDetails(row.id(), row.title(), firstNonBlank(row.company(), HiringCompanyExtractor.extract(row.title())),
				row.recruiterName(), row.recruiterEmail(),
				row.location(), row.remote(), row.receivedAt(), row.savedAt(), row.summary(), row.description(),
				EmailHtmlDisplayRenderer.render(row.htmlBody(), row.description()),
				row.requirements(), row.salaryRange(), row.sourceAccount(), row.sourceSubject(), row.sourceSender(),
				row.responseCount(), row.responseStatus(), row.responseContent(), row.gmailDraftId());
	}

	private static String firstNonBlank(final String preferred, final String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}
}
