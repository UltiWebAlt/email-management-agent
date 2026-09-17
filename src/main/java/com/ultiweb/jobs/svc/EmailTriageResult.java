package com.ultiweb.jobs.svc;

/**
 * The summary and Gmail label assigned to a processed message.
 */
public record EmailTriageResult(String account, String messageId, String summary, String labelName) {
}
