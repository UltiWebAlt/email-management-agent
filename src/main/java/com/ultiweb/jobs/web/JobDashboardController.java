package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DashboardJobDetails;
import com.ultiweb.jobs.svc.dashboard.DashboardSnapshot;
import com.ultiweb.jobs.svc.dashboard.DashboardEventStream;
import com.ultiweb.jobs.svc.dashboard.JobDashboardSvc;
import com.ultiweb.jobs.svc.dashboard.ResponsePreparationWorkflowSvc;
import com.ultiweb.jobs.svc.dashboard.ResponseSelectionResult;
import com.ultiweb.jobs.svc.dashboard.SaveResponseSelectionRequest;
import java.io.IOException;
import javax.mail.MessagingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
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
			@RequestParam(defaultValue = "20") final int pageSize,
			@RequestParam(defaultValue = "newest") final String sort) {
		return dashboardSvc.dashboard(query, page, pageSize, sort);
	}

	@GetMapping("/jobs/{id}")
	public DashboardJobDetails job(@PathVariable final long id) {
		return dashboardSvc.details(id);
	}

	@PostMapping("/selections")
	public ResponseSelectionResult saveSelections(@RequestBody final SaveResponseSelectionRequest request) {
		return responseWorkflowSvc.save(request.changes());
	}

	@PostMapping("/jobs/{id}/response")
	public ResponseSelectionResult prepareResponse(@PathVariable final long id) {
		return responseWorkflowSvc.prepare(id);
	}

	@PostMapping("/jobs/{id}/response/send")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendResponse(@PathVariable final long id) {
		try {
			if (!responseWorkflowSvc.send(id)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"This response is already being sent or is not ready to send");
			}
		} catch (final IOException | MessagingException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gmail could not send this response", exception);
		}
	}
}
