package com.ultiweb.jobs.svc.email;

import java.time.Instant;

public record GmailIncomingReply(String messageId, Instant receivedAt) {
}
