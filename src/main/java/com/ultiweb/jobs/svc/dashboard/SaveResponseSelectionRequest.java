package com.ultiweb.jobs.svc.dashboard;

import java.util.List;

public record SaveResponseSelectionRequest(List<DashboardSelectionChange> changes) {
	public SaveResponseSelectionRequest {
		changes = changes == null ? List.of() : List.copyOf(changes);
	}
}
