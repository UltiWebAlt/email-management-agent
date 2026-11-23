package com.ultiweb.jobs.svc.persistence;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

@Component
public final class JobSearchDatabaseInitializer {
	private static final String SUMMARY_INTRO = "Here is a summary of the email in three concise sentences:";
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
		addPositionColumn("source_account", "ALTER TABLE positions ADD COLUMN source_account TEXT");
		addPositionColumn("source_message_id", "ALTER TABLE positions ADD COLUMN source_message_id TEXT");
		addPositionColumn("source_subject", "ALTER TABLE positions ADD COLUMN source_subject TEXT");
		addPositionColumn("source_sender", "ALTER TABLE positions ADD COLUMN source_sender TEXT");
		addPositionColumn("source_received_at", "ALTER TABLE positions ADD COLUMN source_received_at TEXT");
		addPositionColumn("summary", "ALTER TABLE positions ADD COLUMN summary TEXT");
		addPositionColumn("html_body", "ALTER TABLE positions ADD COLUMN html_body TEXT");
		addPositionColumn("respond_to", "ALTER TABLE positions ADD COLUMN respond_to INTEGER NOT NULL DEFAULT 0");
		addPositionColumn("dismissed", "ALTER TABLE positions ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0");
		addPositionColumn("trash_pending", "ALTER TABLE positions ADD COLUMN trash_pending INTEGER NOT NULL DEFAULT 0");
		jdbcTemplate.update("UPDATE positions SET trash_pending = 0 WHERE trash_pending = 1");
		addResponseColumn("response_status", "ALTER TABLE responses ADD COLUMN response_status TEXT NOT NULL DEFAULT 'RECORDED'");
		addResponseColumn("gmail_draft_id", "ALTER TABLE responses ADD COLUMN gmail_draft_id TEXT");
		final boolean legacySendState = !responseColumns().contains("send_attempted");
		addResponseColumn("send_identifier", "ALTER TABLE responses ADD COLUMN send_identifier TEXT");
		addResponseColumn("send_attempted", "ALTER TABLE responses ADD COLUMN send_attempted INTEGER NOT NULL DEFAULT 0");
		if (legacySendState) {
			jdbcTemplate.update("""
					UPDATE responses SET response_status = 'SEND_UNCONFIRMED', send_attempted = 1
					WHERE response_type = 'INITIAL_RESPONSE' AND response_status IN ('SENDING', 'SEND_FAILED')
					""");
		}
		addResponseColumn("gmail_message_id", "ALTER TABLE responses ADD COLUMN gmail_message_id TEXT");
		addResponseColumn("gmail_thread_id", "ALTER TABLE responses ADD COLUMN gmail_thread_id TEXT");
		addResponseColumn("updated_at", "ALTER TABLE responses ADD COLUMN updated_at TEXT");
		addResponseColumn("sent_at", "ALTER TABLE responses ADD COLUMN sent_at TIMESTAMP");
		addResponseColumn("follow_up_status", "ALTER TABLE responses ADD COLUMN follow_up_status TEXT");
		addResponseColumn("follow_up_content", "ALTER TABLE responses ADD COLUMN follow_up_content TEXT");
		addResponseColumn("follow_up_sent_at", "ALTER TABLE responses ADD COLUMN follow_up_sent_at TIMESTAMP");
		addResponseColumn("follow_up_message_id", "ALTER TABLE responses ADD COLUMN follow_up_message_id TEXT");
		addResponseColumn("recruiter_reply_at", "ALTER TABLE responses ADD COLUMN recruiter_reply_at TIMESTAMP");
		addResponseColumn("recruiter_reply_message_id", "ALTER TABLE responses ADD COLUMN recruiter_reply_message_id TEXT");
		removeSummaryIntroductions();
		createIndexes();
	}

	private void removeSummaryIntroductions() {
		final var rows = jdbcTemplate.query("""
				SELECT id, summary FROM positions
				WHERE summary IS NOT NULL AND LOWER(LTRIM(summary)) LIKE LOWER(?)
				""", (resultSet, rowNumber) -> new SummaryRow(
				resultSet.getLong("id"), resultSet.getString("summary")), SUMMARY_INTRO + "%");
		for (final SummaryRow row : rows) {
			final String summary = row.summary().stripLeading();
			if (summary.regionMatches(true, 0, SUMMARY_INTRO, 0, SUMMARY_INTRO.length())) {
				jdbcTemplate.update("UPDATE positions SET summary = ? WHERE id = ?",
					summary.substring(SUMMARY_INTRO.length()).stripLeading(), row.id());
			}
		}
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
				html_body TEXT,
				respond_to INTEGER NOT NULL DEFAULT 0,
				dismissed INTEGER NOT NULL DEFAULT 0,
				trash_pending INTEGER NOT NULL DEFAULT 0,
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
				response_status TEXT NOT NULL DEFAULT 'RECORDED',
				gmail_draft_id TEXT,
				send_identifier TEXT,
				send_attempted INTEGER NOT NULL DEFAULT 0,
				gmail_message_id TEXT,
				gmail_thread_id TEXT,
				updated_at TEXT,
				sent_at TIMESTAMP,
				follow_up_status TEXT,
				follow_up_content TEXT,
				follow_up_sent_at TIMESTAMP,
				follow_up_message_id TEXT,
				recruiter_reply_at TIMESTAMP,
				recruiter_reply_message_id TEXT,
				position_id BIGINT NOT NULL,
				recruiter_id BIGINT NOT NULL,
				FOREIGN KEY (position_id) REFERENCES positions(id),
				FOREIGN KEY (recruiter_id) REFERENCES recruiters(id)
				)
				""");
	}

	private void addPositionColumn(final String name, final String sql) {
		if (!positionColumns().contains(name)) {
			jdbcTemplate.execute(sql);
		}
	}

	private void addResponseColumn(final String name, final String sql) {
		if (!responseColumns().contains(name)) {
			jdbcTemplate.execute(sql);
		}
	}

	private Set<String> positionColumns() {
		return jdbcTemplate.query("PRAGMA table_info(positions)",
				(resultSet, rowNumber) -> resultSet.getString("name")).stream().collect(Collectors.toUnmodifiableSet());
	}

	private Set<String> responseColumns() {
		return jdbcTemplate.query("PRAGMA table_info(responses)",
				(resultSet, rowNumber) -> resultSet.getString("name")).stream().collect(Collectors.toUnmodifiableSet());
	}

	private void createIndexes() {
		jdbcTemplate.execute("""
				CREATE UNIQUE INDEX IF NOT EXISTS uq_positions_source_message
				ON positions(source_account, source_message_id)
				WHERE source_account IS NOT NULL AND source_message_id IS NOT NULL
				""");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_date_posted ON positions(date_posted)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_created_at ON positions(created_at)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_respond_to ON positions(respond_to)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_positions_recruiter_id ON positions(recruiter_id)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_responses_position_id ON responses(position_id)");
		jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_responses_status ON responses(response_status)");
	}

	private record SummaryRow(long id, String summary) {
	}
}
