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
}
