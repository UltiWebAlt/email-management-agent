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
	private static final String SOURCE_ACCOUNT = "source_account";
	private static final String RECRUITER_EMAIL = "recruiter_email";
	private static final String SOURCE_SUBJECT = "source_subject";
	private final JdbcOperations jdbcTemplate;

	public JdbcJobResponseRepository(final JdbcOperations jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public int updateSelections(final List<ResponseSelectionChange> changes) {
		int updated = 0;
		for (final ResponseSelectionChange change : changes) {
			updated += jdbcTemplate.update("""
					UPDATE positions SET respond_to = ? WHERE id = ? AND dismissed = 0 AND trash_pending = 0
					""",
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

	@Override
	public Optional<ResponseSendCandidate> claimResponseForSending(final long positionId, final Instant updatedAt) {
		// A Gmail draft must already exist before a response can be sent. DRAFT_CREATED is the only
		// state written after the draft is created, so requiring it (plus a nonblank draft id) means
		// a human has had the opportunity to review the content in Gmail first. SEND_FAILED is
		// deliberately absent: a failed send keeps its draft but returns to DRAFT_CREATED through
		// markResponseSendFailed, so retries stay reviewable.
		final int claimed = jdbcTemplate.update("""
				UPDATE responses SET response_status = 'SENDING', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE'
				AND response_status = 'DRAFT_CREATED'
				AND NULLIF(TRIM(gmail_draft_id), '') IS NOT NULL
				AND NULLIF(TRIM(response_content), '') IS NOT NULL
				AND EXISTS (
				SELECT 1 FROM positions p JOIN recruiters recruiter ON recruiter.id = p.recruiter_id
				WHERE p.id = responses.position_id AND p.dismissed = 0
				AND NULLIF(TRIM(p.source_account), '') IS NOT NULL
				AND NULLIF(TRIM(recruiter.email), '') IS NOT NULL)
				""", updatedAt.toString(), positionId);
		if (claimed != 1) {
			return Optional.empty();
		}
		return jdbcTemplate.query("""
				SELECT p.id, p.source_account, recruiter.email AS recruiter_email, p.source_subject,
				response.response_content, response.gmail_draft_id
				FROM positions p
				JOIN recruiters recruiter ON recruiter.id = p.recruiter_id
				JOIN responses response ON response.position_id = p.id
				WHERE p.id = ? AND response.response_type = 'INITIAL_RESPONSE' AND response.response_status = 'SENDING'
				""", (resultSet, rowNumber) -> new ResponseSendCandidate(
				resultSet.getLong("id"), resultSet.getString(SOURCE_ACCOUNT),
				resultSet.getString(RECRUITER_EMAIL), resultSet.getString(SOURCE_SUBJECT),
				resultSet.getString("response_content"), resultSet.getString("gmail_draft_id")), positionId)
				.stream().findFirst();
	}

	@Override
	public void saveSendingDraftId(final long positionId, final String draftId, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET gmail_draft_id = ?, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'SENDING'
				""", draftId, updatedAt.toString(), positionId);
	}

	@Override
	public void markResponseSent(final long positionId, final String draftId, final String gmailMessageId,
			final String gmailThreadId, final String status, final Instant sentAt) {
		jdbcTemplate.update("""
				UPDATE responses SET response_status = ?, gmail_draft_id = ?, gmail_message_id = ?,
				gmail_thread_id = ?, sent_at = ?, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'SENDING'
				""", status, draftId, gmailMessageId, gmailThreadId, sentAt.toString(), sentAt.toString(), positionId);
	}

	@Override
	public void markResponseSendFailed(final long positionId, final Instant updatedAt) {
		// A failed send returns to DRAFT_CREATED, not a terminal failure: the Gmail draft still
		// exists and must remain reviewable before a retry can be claimed for sending again.
		jdbcTemplate.update("""
				UPDATE responses SET response_status = 'DRAFT_CREATED', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND response_status = 'SENDING'
				""", updatedAt.toString(), positionId);
	}

	@Override
	public List<ResponseFollowUpCandidate> findResponsesForFollowUp(final Instant dueBefore) {
		return jdbcTemplate.query("""
				SELECT p.id, p.source_account, recruiter.email AS recruiter_email, recruiter.name AS recruiter_name,
				p.title, p.source_subject, response.response_content, response.follow_up_content,
				response.gmail_message_id, response.gmail_thread_id, response.sent_at, response.follow_up_status,
				response.follow_up_sent_at, response.follow_up_message_id, response.recruiter_reply_at,
				response.recruiter_reply_message_id
				FROM responses response
				JOIN positions p ON p.id = response.position_id
				JOIN recruiters recruiter ON recruiter.id = p.recruiter_id
				WHERE response.response_type = 'INITIAL_RESPONSE'
				AND response.response_status IN ('SENT', 'SENT_LABEL_FAILED')
				AND response.gmail_message_id IS NOT NULL AND response.gmail_thread_id IS NOT NULL
				AND datetime(response.sent_at) <= datetime(?)
				AND (response.follow_up_status IS NULL OR response.follow_up_status IN ('FAILED', 'SENT'))
				AND p.dismissed = 0
				ORDER BY response.sent_at, p.id
				""", JdbcJobResponseRepository::followUpCandidateRow, dueBefore.toString());
	}

	@Override
	public int releaseStaleFollowUpChecks(final Instant staleBefore, final Instant updatedAt) {
		return jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'FAILED', follow_up_needed = 1, updated_at = ?
				WHERE response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				AND datetime(updated_at) <= datetime(?)
				""", updatedAt.toString(), staleBefore.toString());
	}

	@Override
	public Optional<ResponseFollowUpCandidate> claimFollowUp(final long positionId, final Instant updatedAt,
			final Instant dueBefore) {
		final int claimed = jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'CHECKING', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE'
				AND response_status IN ('SENT', 'SENT_LABEL_FAILED')
				AND datetime(sent_at) <= datetime(?)
				AND (follow_up_status IS NULL OR follow_up_status IN ('FAILED', 'SENT'))
				AND EXISTS (SELECT 1 FROM positions p WHERE p.id = responses.position_id AND p.dismissed = 0)
				""", updatedAt.toString(), positionId, dueBefore.toString());
		if (claimed != 1) {
			return Optional.empty();
		}
		return jdbcTemplate.query("""
				SELECT p.id, p.source_account, recruiter.email AS recruiter_email, recruiter.name AS recruiter_name,
				p.title, p.source_subject, response.response_content, response.follow_up_content,
				response.gmail_message_id, response.gmail_thread_id, response.sent_at, response.follow_up_status,
				response.follow_up_sent_at, response.follow_up_message_id, response.recruiter_reply_at,
				response.recruiter_reply_message_id
				FROM responses response JOIN positions p ON p.id = response.position_id
				JOIN recruiters recruiter ON recruiter.id = p.recruiter_id
				WHERE response.position_id = ? AND response.response_type = 'INITIAL_RESPONSE'
				AND response.follow_up_status = 'CHECKING'
				""", JdbcJobResponseRepository::followUpCandidateRow, positionId).stream().findFirst();
	}

	@Override
	public void storeFollowUpContent(final long positionId, final String content, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET follow_up_content = ?, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				""", content, updatedAt.toString(), positionId);
	}

	@Override
	public void markReplied(final long positionId, final String messageId, final Instant receivedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'REPLIED', recruiter_reply_message_id = ?,
				recruiter_reply_at = ?, follow_up_needed = 0, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				""", messageId, receivedAt.toString(), receivedAt.toString(), positionId);
	}

	@Override
	public void markFollowUpSent(final long positionId, final String content, final String messageId,
			final Instant sentAt) {
		jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'SENT', follow_up_content = ?,
				follow_up_sent_at = ?, follow_up_message_id = ?,
				follow_up_needed = 0, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				""", content, sentAt.toString(), messageId, sentAt.toString(), positionId);
	}

	@Override
	public void markFollowUpUnanswered(final long positionId, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'SENT', updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				""", updatedAt.toString(), positionId);
	}

	@Override
	public void markFollowUpFailed(final long positionId, final Instant updatedAt) {
		jdbcTemplate.update("""
				UPDATE responses SET follow_up_status = 'FAILED', follow_up_needed = 1, updated_at = ?
				WHERE position_id = ? AND response_type = 'INITIAL_RESPONSE' AND follow_up_status = 'CHECKING'
				""", updatedAt.toString(), positionId);
	}

	private static ResponseFollowUpCandidate followUpCandidateRow(final ResultSet resultSet, final int rowNumber)
			throws SQLException {
		return new ResponseFollowUpCandidate(resultSet.getLong("id"), resultSet.getString(SOURCE_ACCOUNT),
				resultSet.getString(RECRUITER_EMAIL), resultSet.getString("recruiter_name"),
				resultSet.getString("title"), resultSet.getString(SOURCE_SUBJECT),
				resultSet.getString("response_content"), resultSet.getString("follow_up_content"),
				resultSet.getString("gmail_message_id"),
				resultSet.getString("gmail_thread_id"), instant(resultSet.getString("sent_at")),
				resultSet.getString("follow_up_status"), instant(resultSet.getString("follow_up_sent_at")),
				resultSet.getString("follow_up_message_id"), instant(resultSet.getString("recruiter_reply_at")),
				resultSet.getString("recruiter_reply_message_id"));
	}

	private static Instant instant(final String value) {
		return value == null || value.isBlank() ? null : Instant.parse(value);
	}

	private static ResponseCandidate candidateRow(final ResultSet resultSet, final int rowNumber) throws SQLException {
		return new ResponseCandidate(
				resultSet.getLong("id"),
				resultSet.getLong("recruiter_id"),
				resultSet.getString(SOURCE_ACCOUNT),
				resultSet.getString(SOURCE_SUBJECT),
				resultSet.getString("source_sender"),
				resultSet.getString("description"),
				resultSet.getString("summary"),
				resultSet.getString("title"),
				resultSet.getString(RECRUITER_EMAIL),
				resultSet.getString("recruiter_name"));
	}
}
