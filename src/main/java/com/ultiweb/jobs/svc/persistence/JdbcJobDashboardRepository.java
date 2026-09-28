package com.ultiweb.jobs.svc.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobDashboardRepository implements JobDashboardRepository {
	private static final String JOB_COLUMNS = """
			p.id, p.title, p.company, r.name AS recruiter_name, r.email AS recruiter_email,
			p.location, p.is_remote, p.source_received_at, p.created_at, p.summary, p.respond_to,
			latest_response.response_status, latest_response.response_content
			""";
	private static final String JOB_FROM = """
			FROM positions p
			JOIN recruiters r ON r.id = p.recruiter_id
			LEFT JOIN responses latest_response ON latest_response.id = (
				SELECT MAX(response.id) FROM responses response WHERE response.position_id = p.id)
			""";

	private final JdbcOperations jdbcTemplate;

	public JdbcJobDashboardRepository(final JdbcOperations jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public JobDashboardMetricsRow metrics(final Instant recentSince) {
		return jdbcTemplate.queryForObject("""
				SELECT
					COUNT(*) AS total_jobs,
					SUM(CASE WHEN datetime(p.created_at) >= datetime(?) THEN 1 ELSE 0 END) AS recent_jobs,
					COUNT(DISTINCT p.recruiter_id) AS recruiters,
					(SELECT COUNT(*) FROM responses WHERE follow_up_needed = 1) AS follow_ups,
					MAX(p.created_at) AS last_saved_at
				FROM positions p
				""", (resultSet, rowNumber) -> new JobDashboardMetricsRow(
				resultSet.getLong("total_jobs"),
				resultSet.getLong("recent_jobs"),
				resultSet.getLong("recruiters"),
				resultSet.getLong("follow_ups"),
				instant(resultSet.getString("last_saved_at"))), recentSince.toString());
	}

	@Override
	public long countJobs(final String query) {
		final String normalizedQuery = query == null ? "" : query.strip();
		if (normalizedQuery.isEmpty()) {
			return Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM positions", Long.class),
					"Position count query returned no result");
		}
		final String pattern = "%" + normalizedQuery.toLowerCase(java.util.Locale.ROOT) + "%";
		return Objects.requireNonNull(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM positions p
				JOIN recruiters r ON r.id = p.recruiter_id
				WHERE LOWER(COALESCE(p.title, '')) LIKE ?
					OR LOWER(COALESCE(p.company, '')) LIKE ?
					OR LOWER(COALESCE(p.location, '')) LIKE ?
					OR LOWER(COALESCE(r.name, '')) LIKE ?
					OR LOWER(COALESCE(r.email, '')) LIKE ?
					OR LOWER(COALESCE(p.summary, '')) LIKE ?
				""", Long.class, pattern, pattern, pattern, pattern, pattern, pattern),
				"Filtered position count query returned no result");
	}

	@Override
	public List<JobDashboardRow> findJobs(final String query, final JobSortOrder sortOrder,
			final int offset, final int limit) {
		final String normalizedQuery = query == null ? "" : query.strip();
		if (normalizedQuery.isEmpty()) {
			return jdbcTemplate.query("""
					SELECT ${columns}
					${from}
					ORDER BY ${sortOrder}
					LIMIT ? OFFSET ?
					""".replace("${columns}", JOB_COLUMNS).replace("${from}", JOB_FROM)
					.replace("${sortOrder}", sortOrder.sqlOrderBy()),
					JdbcJobDashboardRepository::jobRow, limit, offset);
		}
		final String pattern = "%" + normalizedQuery.toLowerCase(java.util.Locale.ROOT) + "%";
		return jdbcTemplate.query("""
				SELECT ${columns}
				${from}
				WHERE LOWER(COALESCE(p.title, '')) LIKE ?
					OR LOWER(COALESCE(p.company, '')) LIKE ?
					OR LOWER(COALESCE(p.location, '')) LIKE ?
					OR LOWER(COALESCE(r.name, '')) LIKE ?
					OR LOWER(COALESCE(r.email, '')) LIKE ?
					OR LOWER(COALESCE(p.summary, '')) LIKE ?
				ORDER BY ${sortOrder}
				LIMIT ? OFFSET ?
				""".replace("${columns}", JOB_COLUMNS).replace("${from}", JOB_FROM)
				.replace("${sortOrder}", sortOrder.sqlOrderBy()), JdbcJobDashboardRepository::jobRow,
				pattern, pattern, pattern, pattern, pattern, pattern, limit, offset);
	}

	@Override
	public Optional<JobDashboardDetailsRow> findById(final long id) {
		return jdbcTemplate.query("""
				SELECT ${columns}, p.description, p.html_body, p.requirements, p.salary_range, p.source_account,
					p.source_subject, p.source_sender,
					(SELECT COUNT(*) FROM responses response WHERE response.position_id = p.id) AS response_count,
					latest_response.response_content, latest_response.gmail_draft_id
				FROM positions p
				JOIN recruiters r ON r.id = p.recruiter_id
				LEFT JOIN responses latest_response ON latest_response.id = (
					SELECT MAX(response.id) FROM responses response WHERE response.position_id = p.id)
				WHERE p.id = ?
				""".replace("${columns}", JOB_COLUMNS), (resultSet, rowNumber) -> new JobDashboardDetailsRow(
				resultSet.getLong("id"),
				resultSet.getString("title"),
				resultSet.getString("company"),
				resultSet.getString("recruiter_name"),
				resultSet.getString("recruiter_email"),
				resultSet.getString("location"),
				nullableBoolean(resultSet, "is_remote"),
				instant(resultSet.getString("source_received_at")),
				instant(resultSet.getString("created_at")),
				resultSet.getString("summary"),
				resultSet.getString("description"),
				resultSet.getString("html_body"),
				resultSet.getString("requirements"),
				resultSet.getString("salary_range"),
				resultSet.getString("source_account"),
				resultSet.getString("source_subject"),
				resultSet.getString("source_sender"),
				resultSet.getLong("response_count"),
				resultSet.getString("response_status"),
				resultSet.getString("response_content"),
				resultSet.getString("gmail_draft_id")), id).stream().findFirst();
	}

	private static JobDashboardRow jobRow(final ResultSet resultSet, final int rowNumber) throws SQLException {
		return new JobDashboardRow(
				resultSet.getLong("id"),
				resultSet.getString("title"),
				resultSet.getString("company"),
				resultSet.getString("recruiter_name"),
				resultSet.getString("recruiter_email"),
				resultSet.getString("location"),
				nullableBoolean(resultSet, "is_remote"),
				instant(resultSet.getString("source_received_at")),
				instant(resultSet.getString("created_at")),
				resultSet.getString("summary"),
				resultSet.getBoolean("respond_to"),
				resultSet.getString("response_status"),
				resultSet.getString("response_content"));
	}

	private static Boolean nullableBoolean(final ResultSet resultSet, final String column) throws SQLException {
		final boolean value = resultSet.getBoolean(column);
		return resultSet.wasNull() ? null : value;
	}

	private static Instant instant(final String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (final DateTimeParseException ignored) {
			try {
				return Timestamp.valueOf(value.replace('T', ' ')).toInstant();
			} catch (final IllegalArgumentException invalidTimestamp) {
				return null;
			}
		}
	}
}
