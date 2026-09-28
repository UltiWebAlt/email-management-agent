package com.ultiweb.jobs.svc.email;

/** Normalized text for inference plus sanitized markup for the local dashboard. */
public record ExtractedEmailBody(String text, String html) {
}
