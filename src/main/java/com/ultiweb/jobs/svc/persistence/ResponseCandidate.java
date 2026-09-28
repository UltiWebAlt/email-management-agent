package com.ultiweb.jobs.svc.persistence;

public record ResponseCandidate(
		long positionId,
		long recruiterId,
		String sourceAccount,
		String sourceSubject,
		String sourceSender,
		String originalEmail,
		String summary,
		String title,
		String recruiterEmail,
		String recruiterName) {
}
