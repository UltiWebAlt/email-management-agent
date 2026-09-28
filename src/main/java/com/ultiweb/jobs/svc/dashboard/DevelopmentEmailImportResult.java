package com.ultiweb.jobs.svc.dashboard;

public record DevelopmentEmailImportResult(
		int scanned, int imported, int enriched, int skipped, int notArchitectJobs, int failed) {
}
