package com.ultiweb.jobs.svc;

/**
 * Plain-text representation of a Gmail message used by the triage workflow.
 */
public record EmailMessage(String account, String id, String subject, String from, String body) {
}
