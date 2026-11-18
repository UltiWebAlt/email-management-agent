package com.ultiweb.jobs.svc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JobResponseRepositoryIntegrationTest {
	private static final Instant SENT_AT = Instant.parse("2026-09-20T12:00:00Z");
	private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
	@TempDir Path temporaryDirectory;
	private JdbcTemplate jdbcTemplate;
	private JdbcJobResponseRepository repository;

	@BeforeEach
	void setUp() {
		final var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temporaryDirectory.resolve("responses.db"));
		jdbcTemplate = new JdbcTemplate(dataSource);
		new JobSearchDatabaseInitializer(jdbcTemplate).initialize();
		repository = new JdbcJobResponseRepository(jdbcTemplate);
		insertRecruiter(1, "pat@example.com");
	}

	@Test
	void claimsAnEligibleResponseForSendingAndRecordsTheSentMessage() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertResponse(1, "SAVED_FOR_REVIEW", "Prepared response", "gmail-draft-1", null, null, null);

		// when
		final var candidate = repository.claimResponseForSending(1, NOW).orElseThrow();
		final var duplicateClaim = repository.claimResponseForSending(1, NOW.plusSeconds(1));
		repository.saveSendingDraftId(1, "gmail-draft-2", NOW.plusSeconds(2));
		repository.markResponseSent(1, "gmail-draft-2", "message-1", "thread-1", "SENT", SENT_AT);

		// then
		assertEquals(1, candidate.positionId());
		assertEquals("owner@example.com", candidate.sourceAccount());
		assertEquals("pat@example.com", candidate.recruiterEmail());
		assertEquals("Architect role", candidate.sourceSubject());
		assertEquals("Prepared response", candidate.responseContent());
		assertEquals("gmail-draft-1", candidate.gmailDraftId());
		assertTrue(duplicateClaim.isEmpty());
		assertEquals("SENT", responseValue(1, "response_status"));
		assertEquals("gmail-draft-2", responseValue(1, "gmail_draft_id"));
		assertEquals("message-1", responseValue(1, "gmail_message_id"));
		assertEquals("thread-1", responseValue(1, "gmail_thread_id"));
		assertEquals(SENT_AT.toString(), responseValue(1, "sent_at"));
		assertTrue(repository.claimResponseForSending(1, NOW).isEmpty());
	}

	@Test
	void failedSendsCanBeRetriedAndKeepTheDraftId() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertResponse(1, "DRAFT_CREATED", "Prepared response", "gmail-draft-1", null, null, null);
		repository.claimResponseForSending(1, NOW).orElseThrow();
		repository.saveSendingDraftId(1, "gmail-draft-2", NOW.plusSeconds(1));

		// when
		repository.markResponseSendFailed(1, NOW.plusSeconds(2));
		final var retry = repository.claimResponseForSending(1, NOW.plusSeconds(3)).orElseThrow();

		// then
		assertEquals("gmail-draft-2", retry.gmailDraftId());
		assertEquals("SENDING", responseValue(1, "response_status"));
	}

	@Test
	void refusesToClaimResponsesThatAreNotSendable() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertPosition(2, "owner@example.com", true);
		insertPosition(3, " ", false);
		insertPosition(4, "owner@example.com", false);
		insertPosition(5, "owner@example.com", false);
		insertResponse(1, "GENERATED", "Prepared response", null, null, null, null);
		insertResponse(2, "SAVED_FOR_REVIEW", "Prepared response", null, null, null, null);
		insertResponse(3, "SAVED_FOR_REVIEW", "Prepared response", null, null, null, null);
		insertResponse(4, "SAVED_FOR_REVIEW", "   ", null, null, null, null);
		insertResponse(5, "FAILED", "Prepared response", null, null, null, null);

		// when / then
		for (long positionId = 1; positionId <= 5; positionId++) {
			assertTrue(repository.claimResponseForSending(positionId, NOW).isEmpty(),
					"position " + positionId + " should not be sendable");
		}
	}

	@Test
	void selectionAndInitialResponseGenerationCanRetryOnlyFailedResponses() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertPosition(2, "owner@example.com", false);
		insertPosition(3, "owner@example.com", false);
		insertPosition(4, "owner@example.com", true);
		insertResponse(2, "GENERATED", "Existing response", null, null, null, null);
		insertResponse(3, "FAILED", "Incomplete response", null, null, null, null);

		// when
		final int changed = repository.updateSelections(List.of(
				new ResponseSelectionChange(1, true),
				new ResponseSelectionChange(2, true),
				new ResponseSelectionChange(3, true),
				new ResponseSelectionChange(4, true),
				new ResponseSelectionChange(99, true)));
		final List<Long> selected = repository.findSelectedWithoutResponse();
		final var candidate = repository.claimResponse(1, NOW).orElseThrow();
		final var duplicateClaim = repository.claimResponse(1, NOW.plusSeconds(1));
		repository.markResponseFailed(1, NOW.plusSeconds(2));
		final String failedStatus = responseValue(1, "response_status");
		final var retry = repository.claimResponse(1, NOW.plusSeconds(3)).orElseThrow();
		repository.saveGeneratedResponse(1, "Completed response", "GENERATED", NOW.plusSeconds(4));

		// then
		assertEquals(3, changed);
		assertEquals(List.of(1L, 3L), selected);
		assertTrue(repository.isSelected(1));
		assertTrue(duplicateClaim.isEmpty());
		assertEquals(1, candidate.positionId());
		assertEquals(1, candidate.recruiterId());
		assertEquals("owner@example.com", candidate.sourceAccount());
		assertEquals("Architect role", candidate.sourceSubject());
		assertEquals("Pat <pat@example.com>", candidate.sourceSender());
		assertEquals("Role description", candidate.originalEmail());
		assertEquals("Architect opportunity", candidate.summary());
		assertEquals("Architect role", candidate.title());
		assertEquals("pat@example.com", candidate.recruiterEmail());
		assertEquals("Pat Recruiter", candidate.recruiterName());
		assertEquals("FAILED", failedStatus);
		assertEquals("GENERATED", responseValue(1, "response_status"));
		assertEquals("Completed response", responseValue(1, "response_content"));
		assertTrue(repository.findSelectedWithoutResponse().contains(3L));
		assertTrue(repository.claimResponse(4, NOW).isEmpty());
		assertTrue(repository.claimResponse(99, NOW).isEmpty());
		assertEquals(1, retry.positionId());
	}

	@Test
	void findsDueResponsesAndClaimsOnlyOnceUntilFollowUpFinishes() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertPosition(2, "owner@example.com", false);
		insertPosition(3, "owner@example.com", true);
		insertPosition(4, "owner@example.com", false);
		insertResponse(1, "SENT", "Initial response", null, "message-1", "thread-1", SENT_AT);
		insertResponse(2, "SENT", "Initial response", null, "message-2", "thread-2", NOW);
		insertResponse(3, "SENT", "Initial response", null, "message-3", "thread-3", SENT_AT);
		insertResponse(4, "SENT", "Initial response", null, null, "thread-4", SENT_AT);

		// when
		final List<ResponseFollowUpCandidate> due = repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60));
		final var candidate = repository.claimFollowUp(1, NOW, NOW.minusSeconds(48 * 60 * 60)).orElseThrow();
		final var duplicateClaim = repository.claimFollowUp(1, NOW.plusSeconds(1), NOW.minusSeconds(48 * 60 * 60));
		repository.storeFollowUpContent(1, "Checking in", NOW.plusSeconds(2));
		repository.markFollowUpSent(1, "Checking in", "follow-up-1", NOW.plusSeconds(3));

		// then
		assertEquals(List.of(1L), due.stream().map(ResponseFollowUpCandidate::positionId).toList());
		assertEquals("pat@example.com", candidate.recruiterEmail());
		assertEquals("thread-1", candidate.gmailThreadId());
		assertEquals(SENT_AT, candidate.sentAt());
		assertTrue(duplicateClaim.isEmpty());
		assertEquals("SENT", responseValue(1, "follow_up_status"));
		assertEquals("Checking in", responseValue(1, "follow_up_content"));
		assertEquals("follow-up-1", responseValue(1, "follow_up_message_id"));
		assertEquals("0", responseValue(1, "follow_up_needed"));
	}

	@Test
	void releasesStaleChecksAndRecordsRepliesAndFailures() {
		// given
		insertPosition(1, "owner@example.com", false);
		insertPosition(2, "owner@example.com", false);
		insertPosition(3, "owner@example.com", false);
		insertResponse(1, "SENT", "Initial response", null, "message-1", "thread-1", SENT_AT);
		insertResponse(2, "SENT", "Initial response", null, "message-2", "thread-2", SENT_AT);
		insertResponse(3, "SENT", "Initial response", null, "message-3", "thread-3", SENT_AT);
		repository.claimFollowUp(1, NOW.minusSeconds(3 * 60 * 60), NOW);
		repository.claimFollowUp(2, NOW, NOW);
		repository.claimFollowUp(3, NOW, NOW);

		// when
		final int released = repository.releaseStaleFollowUpChecks(NOW.minusSeconds(2 * 60 * 60), NOW);
		repository.markReplied(2, "reply-2", NOW.plusSeconds(10));
		repository.markFollowUpFailed(3, NOW.plusSeconds(10));
		final var retry = repository.claimFollowUp(3, NOW.plusSeconds(11), NOW).orElseThrow();
		repository.markFollowUpUnanswered(3, NOW.plusSeconds(12));

		// then
		assertEquals(1, released);
		assertEquals("FAILED", responseValue(1, "follow_up_status"));
		assertEquals("1", responseValue(1, "follow_up_needed"));
		assertEquals("REPLIED", responseValue(2, "follow_up_status"));
		assertEquals("reply-2", responseValue(2, "recruiter_reply_message_id"));
		assertEquals("0", responseValue(2, "follow_up_needed"));
		assertEquals(3, retry.positionId());
		assertEquals("SENT", responseValue(3, "follow_up_status"));
		assertTrue(repository.claimFollowUp(3, NOW.plusSeconds(13), NOW).isPresent());
	}

	private void insertRecruiter(final long id, final String email) {
		jdbcTemplate.update("""
				INSERT INTO recruiters (id, company, created_at, email, name)
				VALUES (?, ?, ?, ?, ?)
				""", id, "Example Corp", SENT_AT.toString(), email, "Pat Recruiter");
	}

	private void insertPosition(final long id, final String sourceAccount, final boolean dismissed) {
		jdbcTemplate.update("""
				INSERT INTO positions (id, company, created_at, description, title, recruiter_id,
					source_account, source_message_id, source_subject, source_sender, source_received_at, summary,
					dismissed)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, "Example Corp", SENT_AT.toString(), "Role description", "Architect role", 1,
				sourceAccount, "source-" + id, "Architect role", "Pat <pat@example.com>", SENT_AT.toString(),
				"Architect opportunity", dismissed ? 1 : 0);
	}

	private void insertResponse(final long positionId, final String status, final String content,
			final String draftId, final String messageId, final String threadId, final Instant sentAt) {
		jdbcTemplate.update("""
				INSERT INTO responses (created_at, response_type, response_status, response_content, gmail_draft_id,
					gmail_message_id, gmail_thread_id, sent_at, follow_up_needed, position_id, recruiter_id)
				VALUES (?, 'INITIAL_RESPONSE', ?, ?, ?, ?, ?, ?, 0, ?, 1)
				""", SENT_AT.toString(), status, content, draftId, messageId, threadId,
				sentAt == null ? null : sentAt.toString(), positionId);
	}

	private String responseValue(final long positionId, final String column) {
		return jdbcTemplate.queryForObject("SELECT " + column + " FROM responses WHERE position_id = ?", String.class,
				positionId);
	}
}
