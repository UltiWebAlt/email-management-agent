package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DashboardJobDetails;
import com.ultiweb.jobs.svc.dashboard.DashboardSnapshot;
import com.ultiweb.jobs.svc.dashboard.DashboardEventStream;
import com.ultiweb.jobs.svc.dashboard.JobDashboardSvc;
import com.ultiweb.jobs.svc.dashboard.ResponsePreparationWorkflowSvc;
import com.ultiweb.jobs.svc.dashboard.ResponseSelectionResult;
import com.ultiweb.jobs.svc.dashboard.SaveResponseSelectionRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/dashboard")
public final class JobDashboardController {
	private final JobDashboardSvc dashboardSvc;
	private final ResponsePreparationWorkflowSvc responseWorkflowSvc;
	private final DashboardEventStream eventStream;

	public JobDashboardController(final JobDashboardSvc dashboardSvc,
			final ResponsePreparationWorkflowSvc responseWorkflowSvc, final DashboardEventStream eventStream) {
		this.dashboardSvc = dashboardSvc;
		this.responseWorkflowSvc = responseWorkflowSvc;
		this.eventStream = eventStream;
	}

	@GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events() {
		return eventStream.connect();
	}

	@GetMapping
	public DashboardSnapshot dashboard(@RequestParam(defaultValue = "") final String query,
			@RequestParam(defaultValue = "0") final int page,
			@RequestParam(defaultValue = "20") final int pageSize) {
		return dashboardSvc.dashboard(query, page, pageSize);
	}

	@GetMapping("/jobs/{id}")
	public DashboardJobDetails job(@PathVariable final long id) {
		return dashboardSvc.details(id);
	}

	@PostMapping("/selections")
	public ResponseSelectionResult saveSelections(@RequestBody final SaveResponseSelectionRequest request) {
		return responseWorkflowSvc.save(request.changes());
	}
}
