package com.ultiweb.jobs.svc.persistence;

/** Signals that an existing opportunity was enriched from its source email. */
public record ArchitectOpportunityUpdatedEvent(String messageId) {
}
