package com.ultiweb.jobs.svc.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobResponseRepository implements JobResponseRepository {
	private final JdbcOperations jdbcTemplate;

	public JdbcJobResponseRepository(final JdbcOperations jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public int updateSelections(final List<ResponseSelectionChange> changes) {
		int updated = 0;
		for (final ResponseSelectionChange change : changes) {
			updated += jdbcTemplate.update("UPDATE positions SET respond_to = ? WHERE id = ?",
					change.selected() ? 1 : 0, change.positionId());
		}
		return updated;
	}

	@Override
	public List<Long> findSelectedWithoutResponse() {
		return jdbcTemplate.queryForList("""
				SELECT p.id
				FROM positions p
				WHERE p.respond_to = 1
				AND NOT EXISTS (
				SELECT 1 FROM responses r
				WHERE r.position_id = p.id
				AND r.response_type = 'INITIAL_RESPONSE'
				AND r.response_status <> 'FAILED')
				ORDER BY p.id
				""", Long.class);
	}

	@Override
	public boolean isSelected(final long positionId) {
		final Integer selected = jdbcTemplate.queryForObject(
				"SELECT respond_to FROM positions WHERE id = ?", Integer.class, positionId);
		return Integer.valueOf(1).equals(selected);
	}

	@Override
	public Optional<ResponseCandidate> claimResponse(final long positionId, final Instant now) {
		final int inserted = jdbcTemplate.update("""
				INSERT INTO responses (created_at, response_type, response_status, updated_at, position_id, recruiter_id)
				SELECT ?, 'INITIAL_RESPONSE', 'GENERATING', ?, p.id, p.recruiter_id
				FROM positions p
				WHERE p.id = ? AND p.respond_to = 1
				AND NOT EXISTS (
				SELECT 1 FROM responses r WHERE r.position_id = p.id AND r.response_type = 'INITIAL_RESPONSE')
				""", now.toString(), now.toString(), positionId);
		boolean claimed = inserted == 1;
		if (!claimed) {
			claimed = jdbcTemplate.update("""
					UPDATE responses SET response_status = 'GENERATING', updated_at = ?
					WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'FAILED'
					AND EXISTS (SELECT 1 FROM positions p WHERE p.id = ? AND p.respond_to = 1)
					""", now.toString(), positionId, positionId) == 1;
		}
		if (!claimed) {
			return Optional.empty();
		}
		return jdbcTemplate.query("""
				SELECT p.id, p.recruiter_id, p.source_account, p.source_subject, p.source_sender,
				p.description, p.summary, p.title, r.email AS recruiter_email, r.name AS recruiter_name
				FROM positions p
				JOIN recruiters r ON r.id = p.recruiter_id
				JOIN responses response ON response.position_id = p.id
				WHERE p.id = ? AND p.respond_to = 1 AND response.response_type = 'INITIAL_RESPONSE'
				AND response.response_status = 'GENERATING'
				""", JdbcJobResponseRepository::candidateRow, positionId).stream().findFirst();
	}

	@Override
	public void saveGeneratedResponse(final long positionId, final String response,
			final String status, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET response_content = ?, response_status = ?, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'GENERATING'
				""", response, status, updatedAt.toString(), positionId);
	}

	@Override
	public void saveDraftId(final long positionId, final String draftId, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET gmail_draft_id = ?, response_status = 'DRAFT_CREATED', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'GENERATED'
				""", draftId, updatedAt.toString(), positionId);
	}

	@Override
	public void markResponseFailed(final long positionId, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET response_status = 'FAILED', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status IN ('GENERATING', 'GENERATED')
				""", updatedAt.toString(), positionId);
	}

	private static ResponseCandidate candidateRow(final ResultSet resultSet, final int rowNumber) throws SQLException {
		return new ResponseCandidate(
				resultSet.getLong("id"),
				resultSet.getLong("recruiter_id"),
				resultSet.getString("source_account"),
				resultSet.getString("source_subject"),
				resultSet.getString("source_sender"),
				resultSet.getString("description"),
				resultSet.getString("summary"),
				resultSet.getString("title"),
				resultSet.getString("recruiter_email"),
				resultSet.getString("recruiter_name"));
	}
}
