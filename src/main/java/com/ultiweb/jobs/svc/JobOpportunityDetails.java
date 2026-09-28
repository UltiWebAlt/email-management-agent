package com.ultiweb.jobs.svc;

/** Structured role information inferred from the source email. Unknown values remain null. */
public record JobOpportunityDetails(
		String title,
		String company,
		String location,
		Boolean remote,
		String salaryRange,
		String requirements) {
	public static JobOpportunityDetails empty() {
		return new JobOpportunityDetails(null, null, null, null, null, null);
	}
}
