package com.ultiweb.jobs.svc.email;

import java.time.Instant;

public record GmailSentMessage(String draftId, String messageId, String threadId, Instant sentAt, boolean labelApplied) {
}
