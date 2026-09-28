package com.ultiweb.jobs.svc.dashboard;

public record DevelopmentEmailImportResult(int scanned, int imported, int skipped, int failed) {
}
