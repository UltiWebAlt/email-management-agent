package com.ultiweb.jobs.svc.dashboard;

public final class DashboardJobNotFoundException extends RuntimeException {
	public DashboardJobNotFoundException(final long id) {
		super("Architect job " + id + " was not found");
	}
}
