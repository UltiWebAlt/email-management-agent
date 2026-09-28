package com.ultiweb.jobs.svc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ultiweb.jobs.svc.email.EmailMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ArchitectJobPersistenceSvcTest {
	private SingleConnectionDataSource dataSource;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
		jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@AfterEach
	void tearDown() {
		dataSource.destroy();
	}

	@Test
	void persistsOnlyOnePositionPerGmailAccountAndMessage() {
		// given
		new JobSearchDatabaseInitializer(jdbcTemplate).initialize();
		final var repository = new JdbcArchitectJobRepository(jdbcTemplate);
		final Instant now = Instant.parse("2026-09-27T18:00:00Z");
		final var service = new ArchitectJobPersistenceSvc(repository, Clock.fixed(now, ZoneOffset.UTC));
		final var email = new EmailMessage("owner@example.com", "gmail-id", "Principal Solutions Architect",
				"Taylor Recruiter <Taylor.Recruiter@Example.com>", "Role description and requirements.",
				Instant.parse("2026-09-26T12:30:00Z"));

		// when
		final boolean firstInsert = service.persist(email, "A principal solutions architect opportunity.");
		final boolean duplicateInsert = service.persist(email, "A duplicate summary must not create another row.");

		// then
		assertTrue(firstInsert);
		assertFalse(duplicateInsert);
		assertTrue(service.isPersisted(email));
		assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM positions", Integer.class));
		assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recruiters", Integer.class));
		assertEquals("taylor.recruiter@example.com",
				jdbcTemplate.queryForObject("SELECT email FROM recruiters", String.class));
		assertEquals("Principal Solutions Architect",
				jdbcTemplate.queryForObject("SELECT title FROM positions", String.class));
		assertEquals("Role description and requirements.",
				jdbcTemplate.queryForObject("SELECT description FROM positions", String.class));
		assertEquals("A principal solutions architect opportunity.",
				jdbcTemplate.queryForObject("SELECT summary FROM positions", String.class));
		assertEquals("2026-09-26T12:30:00Z",
				jdbcTemplate.queryForObject("SELECT source_received_at FROM positions", String.class));
	}

	@Test
	void additiveMigrationPreservesExistingPositionInformation() {
		// given
		jdbcTemplate.execute("""
				CREATE TABLE recruiters (
					id INTEGER PRIMARY KEY, company VARCHAR(255), created_at TIMESTAMP,
					email VARCHAR(255) NOT NULL UNIQUE, linkedin_profile VARCHAR(255),
					name VARCHAR(255), phone VARCHAR(255))
				""");
		jdbcTemplate.execute("""
				CREATE TABLE positions (
					id INTEGER PRIMARY KEY, company VARCHAR(255), created_at TIMESTAMP,
					date_posted TIMESTAMP, description TEXT, is_remote BOOLEAN,
					location VARCHAR(255), requirements TEXT, salary_range VARCHAR(255),
					title VARCHAR(255) NOT NULL, recruiter_id BIGINT NOT NULL)
				""");
		jdbcTemplate.update("INSERT INTO recruiters (id, email, name) VALUES (1, ?, ?)",
				"existing@example.com", "Existing Recruiter");
		jdbcTemplate.update("INSERT INTO positions (id, title, description, recruiter_id) VALUES (1, ?, ?, 1)",
				"Existing Architect", "Existing description");

		// when
		new JobSearchDatabaseInitializer(jdbcTemplate).initialize();

		// then
		assertEquals("Existing Architect", jdbcTemplate.queryForObject(
				"SELECT title FROM positions WHERE id = 1", String.class));
		assertEquals("Existing description", jdbcTemplate.queryForObject(
				"SELECT description FROM positions WHERE id = 1", String.class));
		final Set<String> columns = jdbcTemplate.query("PRAGMA table_info(positions)",
				(resultSet, rowNumber) -> resultSet.getString("name")).stream().collect(Collectors.toSet());
		assertTrue(columns.containsAll(Set.of("source_account", "source_message_id", "source_subject",
				"source_sender", "source_received_at", "summary")));
	}
}
