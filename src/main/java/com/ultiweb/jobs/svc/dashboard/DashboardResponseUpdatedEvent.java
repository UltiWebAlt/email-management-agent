package com.ultiweb.jobs.svc.dashboard;

/** Signals that a response status or body changed and dashboard data should refresh. */
public record DashboardResponseUpdatedEvent(long positionId) {
}
