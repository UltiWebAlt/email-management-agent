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
		final var jobs = repository.findJobs("cloud", JobSortOrder.NEWEST, 0, 20);
		final var newestFirst = repository.findJobs("", JobSortOrder.NEWEST, 0, 20);
		final var oldestFirst = repository.findJobs("", JobSortOrder.OLDEST, 0, 20);
		final var titleAZ = repository.findJobs("", JobSortOrder.TITLE_ASC, 0, 20);
		final var details = repository.findById(1);

		// then
		assertEquals(2, metrics.totalJobs());
		assertEquals(1, metrics.recentJobs());
		assertEquals(1, metrics.recruiters());
		assertEquals(1, metrics.followUps());
		assertEquals(1, repository.countJobs("cloud"));
		assertEquals(1, jobs.size());
		assertEquals("Cloud architect", jobs.getFirst().title());
		assertEquals("Cloud architect", newestFirst.getFirst().title());
		assertEquals("Data architect", oldestFirst.getFirst().title());
		assertEquals("Cloud architect", titleAZ.getFirst().title());
		assertEquals("Follow up", jobs.getFirst().responseContent());
		assertTrue(details.isPresent());
		assertEquals("Complete stored email", details.orElseThrow().description());
		assertEquals("<p>Formatted source</p>", details.orElseThrow().htmlBody());
		assertEquals(1, details.orElseThrow().responseCount());
		assertEquals("RECORDED", details.orElseThrow().responseStatus());
	}

	@Test
	void persistsPreparedResponsesForDevelopmentAndProductionDraftFlows() {
		// given
		insertRecruiter();
		insertPosition(1, "Dev architect", "Example Corp", "2026-09-27T12:00:00Z", "Dev role");
		insertPosition(2, "Production architect", "Example Corp", "2026-09-28T12:00:00Z", "Production role");
		jdbcTemplate.update("UPDATE positions SET respond_to = 1");
		final var responseRepository = new JdbcJobResponseRepository(jdbcTemplate);
		final Instant now = Instant.parse("2026-09-28T12:00:00Z");

		// when
		responseRepository.claimResponse(1, now).orElseThrow();
		responseRepository.saveGeneratedResponse(1, "Development reply", "SAVED_FOR_REVIEW", now);
		responseRepository.claimResponse(2, now).orElseThrow();
		responseRepository.saveGeneratedResponse(2, "Production reply", "GENERATED", now);
		responseRepository.saveDraftId(2, "gmail-draft-2", now);

		// then
		assertEquals("Development reply", jdbcTemplate.queryForObject(
				"SELECT response_content FROM responses WHERE position_id = 1", String.class));
		assertEquals("SAVED_FOR_REVIEW", jdbcTemplate.queryForObject(
				"SELECT response_status FROM responses WHERE position_id = 1", String.class));
		assertEquals("Production reply", jdbcTemplate.queryForObject(
				"SELECT response_content FROM responses WHERE position_id = 2", String.class));
		assertEquals("DRAFT_CREATED", jdbcTemplate.queryForObject(
				"SELECT response_status FROM responses WHERE position_id = 2", String.class));
		assertEquals("gmail-draft-2", jdbcTemplate.queryForObject(
				"SELECT gmail_draft_id FROM responses WHERE position_id = 2", String.class));
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
					source_account, source_message_id, source_subject, source_sender, source_received_at, summary, html_body)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, company, createdAt, "Complete stored email", title, 1, "owner@example.com",
				"message-" + id, title, "Pat <pat@example.com>", createdAt, summary, "<p>Formatted source</p>");
	}
}
