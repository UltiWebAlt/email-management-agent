package com.ultiweb.jobs.svc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JobDashboardRepositoryIntegrationTest {
	@TempDir Path temporaryDirectory;
	private JdbcTemplate jdbcTemplate;
	private JobDashboardRepository repository;

	@BeforeEach
	void setUp() {
		final var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temporaryDirectory.resolve("dashboard.db"));
		jdbcTemplate = new JdbcTemplate(dataSource);
		new JobSearchDatabaseInitializer(jdbcTemplate).initialize();
		repository = new JdbcJobDashboardRepository(jdbcTemplate);
	}

	@Test
	void reportsMetricsAndSearchesPersistedJobs() {
		// given
		insertRecruiter();
		insertPosition(1, "Cloud architect", "Example Corp", "2026-09-27T12:00:00Z", "Cloud migration role");
		insertPosition(2, "Data architect", "Data Corp", "2026-09-10T12:00:00Z", "Warehouse role");
		jdbcTemplate.update("""
				INSERT INTO responses (created_at, follow_up_needed, response_content, response_type,
					sent_at, position_id, recruiter_id)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", "2026-09-28T12:00:00Z", true, "Follow up", "FOLLOW_UP", null, 1, 1);

		// when
		final JobDashboardMetricsRow metrics = repository.metrics(Instant.parse("2026-09-21T12:00:00Z"));
		final var jobs = repository.findJobs("cloud", 0, 20);
		final var details = repository.findById(1);

		// then
		assertEquals(2, metrics.totalJobs());
		assertEquals(1, metrics.recentJobs());
		assertEquals(1, metrics.recruiters());
		assertEquals(1, metrics.followUps());
		assertEquals(1, repository.countJobs("cloud"));
		assertEquals(1, jobs.size());
		assertEquals("Cloud architect", jobs.getFirst().title());
		assertTrue(details.isPresent());
		assertEquals("Complete stored email", details.orElseThrow().description());
		assertEquals(1, details.orElseThrow().responseCount());
		assertEquals("RECORDED", details.orElseThrow().responseStatus());
	}

	private void insertRecruiter() {
		jdbcTemplate.update("""
				INSERT INTO recruiters (id, company, created_at, email, name)
				VALUES (?, ?, ?, ?, ?)
				""", 1, "Example Corp", "2026-09-27T12:00:00Z", "pat@example.com", "Pat Recruiter");
	}

	private void insertPosition(final long id, final String title, final String company,
			final String createdAt, final String summary) {
		jdbcTemplate.update("""
				INSERT INTO positions (id, company, created_at, description, title, recruiter_id,
					source_account, source_message_id, source_subject, source_sender, source_received_at, summary)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, company, createdAt, "Complete stored email", title, 1, "owner@example.com",
				"message-" + id, title, "Pat <pat@example.com>", createdAt, summary);
	}
}
