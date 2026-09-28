package com.ultiweb.jobs.svc.persistence;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

@Component
public final class JobSearchDatabaseInitializer {
	private final JdbcOperations jdbcTemplate;

	public JobSearchDatabaseInitializer(final JdbcOperations jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@PostConstruct
	public void initialize() {
		jdbcTemplate.execute("PRAGMA foreign_keys = ON");
		jdbcTemplate.execute("PRAGMA busy_timeout = 5000");
		jdbcTemplate.queryForObject("PRAGMA journal_mode = WAL", String.class);
		createBaseTables();
		addPositionColumn("source_account", "TEXT");
		addPositionColumn("source_message_id", "TEXT");
		addPositionColumn("source_subject", "TEXT");
		addPositionColumn("source_sender", "TEXT");
		addPositionColumn("source_received_at", "TEXT");
		addPositionColumn("summary", "TEXT");
		createIndexes();
	}

	private void createBaseTables() {
		jdbcTemplate.execute("""
				CREATE TABLE IF NOT EXISTS recruiters (
					id INTEGER PRIMARY KEY,
					company VARCHAR(255),
					created_at TIMESTAMP,
					email VARCHAR(255) NOT NULL UNIQUE,
					linkedin_profile VARCHAR(255),
					name VARCHAR(255),
					phone VARCHAR(255)
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE IF NOT EXISTS positions (
					id INTEGER PRIMARY KEY,
					company VARCHAR(255),
					created_at TIMESTAMP,
					date_posted TIMESTAMP,
					description TEXT,
					is_remote BOOLEAN,
					location VARCHAR(255),
					requirements TEXT,
					salary_range VARCHAR(255),
					title VARCHAR(255) NOT NULL,
					recruiter_id BIGINT NOT NULL,
					source_account TEXT,
					source_message_id TEXT,
					source_subject TEXT,
					source_sender TEXT,
					source_received_at TEXT,
					summary TEXT,
					FOREIGN KEY (recruiter_id) REFERENCES recruiters(id)
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE IF NOT EXISTS responses (
					id INTEGER PRIMARY KEY,
					created_at TIMESTAMP,
					follow_up_needed BOOLEAN,
					response_content TEXT,
					response_type VARCHAR(255) CHECK (response_type IN (
						'INITIAL_RESPONSE', 'FOLLOW_UP', 'INTERVIEW_CONFIRMATION', 'THANK_YOU', 'DECLINED')),
					sent_at TIMESTAMP,
					position_id BIGINT NOT NULL,
					recruiter_id BIGINT NOT NULL,
					FOREIGN KEY (position_id) REFERENCES positions(id),
					FOREIGN KEY (recruiter_id) REFERENCES recruiters(id)
				)
				""");
	}

	private void addPositionColumn(final String name, final String definition) {
		if (!positionColumns().contains(name)) {
			jdbcTemplate.execute("ALTER TABLE positions ADD COLUMN " + name + " " + definition);
		}
	}

	private Set<String> positionColumns() {
		return jdbcTemplate.query("PRAGMA table_info(positions)",
				(resultSet, rowNumber) -> resultSet.getString("name")).stream().collect(Collectors.toUnmodifiableSet());
	}

	private void createIndexes() {
		jdbcTemplate.execute("""
				CREATE UNIQUE INDEX IF NOT EXISTS uq_positions_source_message
				ON positions(source_account, source_message_id)
				WHERE source_account IS NOT NULL AND source_message_id IS NOT NULL
				""");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_date_posted ON positions(date_posted)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_recruiter_id ON positions(recruiter_id)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_responses_position_id ON responses(position_id)");
	}
}
