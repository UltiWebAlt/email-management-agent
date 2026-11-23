package com.ultiweb.jobs.svc.persistence;

public record ResponseSendCandidate(
		long positionId,
		String sourceAccount,
		String recruiterEmail,
		String sourceSubject,
		String responseContent,
		String gmailDraftId,
		String sendIdentifier,
		boolean reconcileOnly) {
}
