package com.ultiweb.jobs.svc.email;

/** Normalized text for inference plus source HTML, sanitized when rendered in the dashboard. */
public record ExtractedEmailBody(String text, String html) {
}
