package com.ultiweb.jobs.svc.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArchitectJobRepository implements ArchitectJobRepository {
	private static final String INSERT_RECRUITER = """
			INSERT INTO recruiters (company, created_at, email, name)
			VALUES (?, ?, ?, ?)
			ON CONFLICT(email) DO UPDATE SET
				company = COALESCE(recruiters.company, excluded.company),
				name = COALESCE(recruiters.name, excluded.name)
			""";
	private static final String INSERT_POSITION = """
			INSERT INTO positions (
				company, created_at, date_posted, description, is_remote, location, requirements,
				salary_range, title, recruiter_id, source_account, source_message_id, source_subject,
				source_sender, source_received_at, summary)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(source_account, source_message_id)
			WHERE source_account IS NOT NULL AND source_message_id IS NOT NULL
			DO NOTHING
			""";

	private final JdbcOperations jdbcTemplate;

	public JdbcArchitectJobRepository(final JdbcOperations jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean existsBySource(final String account, final String messageId) {
		final Integer exists = jdbcTemplate.queryForObject("""
				SELECT EXISTS(
					SELECT 1 FROM positions WHERE source_account = ? AND source_message_id = ?
				)
				""", Integer.class, account, messageId);
		return Integer.valueOf(1).equals(exists);
	}

	@Override
	public boolean saveIfAbsent(final ArchitectJobRecord job) {
		Objects.requireNonNull(job, "job");
		jdbcTemplate.update(INSERT_RECRUITER, null, job.createdAt().toString(), job.recruiterEmail(), job.recruiterName());
		final Long recruiterId = jdbcTemplate.queryForObject(
				"SELECT id FROM recruiters WHERE email = ?", Long.class, job.recruiterEmail());
		final int inserted = jdbcTemplate.update(INSERT_POSITION,
				null,
				job.createdAt().toString(),
				instantText(job.sourceReceivedAt()),
				job.description(),
				null,
				null,
				null,
				null,
				job.title(),
				recruiterId,
				job.sourceAccount(),
				job.sourceMessageId(),
				job.sourceSubject(),
				job.sourceSender(),
				instantText(job.sourceReceivedAt()),
				job.summary());
		return inserted == 1;
	}

	private static String instantText(final java.time.Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
