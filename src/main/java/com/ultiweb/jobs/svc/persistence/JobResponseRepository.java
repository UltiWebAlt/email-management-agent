package com.ultiweb.jobs.svc.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobResponseRepository {
	int updateSelections(List<ResponseSelectionChange> changes);

	List<Long> findSelectedWithoutResponse();

	boolean isSelected(long positionId);

	Optional<ResponseCandidate> claimResponse(long positionId, Instant now);

	void saveGeneratedResponse(long positionId, String response, String status, Instant updatedAt);

	void saveDraftId(long positionId, String draftId, Instant updatedAt);

	void markResponseFailed(long positionId, Instant updatedAt);

	Optional<ResponseSendCandidate> claimResponseForSending(long positionId, Instant updatedAt, String sendIdentifier,
			Instant staleBefore);

	void markSendAttemptStarted(long positionId, Instant updatedAt);

	void saveSendingDraftId(long positionId, String draftId, Instant updatedAt);

	void markResponseSent(long positionId, String draftId, String gmailMessageId, String gmailThreadId,
			String status, Instant sentAt);

	void markResponseSendFailed(long positionId, Instant updatedAt);

	void markResponseSendUnconfirmed(long positionId, Instant updatedAt);

	List<ResponseFollowUpCandidate> findResponsesForFollowUp(Instant dueBefore);

	int releaseStaleFollowUpChecks(Instant staleBefore, Instant updatedAt);

	Optional<ResponseFollowUpCandidate> claimFollowUp(long positionId, Instant updatedAt, Instant dueBefore);

	void storeFollowUpContent(long positionId, String content, Instant updatedAt);

	void markReplied(long positionId, String messageId, Instant receivedAt);

	void markFollowUpSent(long positionId, String content, String messageId, Instant sentAt);

	void markFollowUpUnanswered(long positionId, Instant updatedAt);

	void markFollowUpFailed(long positionId, Instant updatedAt);
}
