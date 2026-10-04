package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DevelopmentArchitectEmailImportSvc;
import com.ultiweb.jobs.svc.dashboard.DevelopmentEmailImportResult;
import com.ultiweb.jobs.svc.dashboard.DashboardEventStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/dev")
@Profile("dev")
public final class DevelopmentEmailImportController {
	private final DevelopmentArchitectEmailImportSvc importSvc;
	private final DashboardEventStream eventStream;
	private final AtomicBoolean importRunning = new AtomicBoolean();

	public DevelopmentEmailImportController(final DevelopmentArchitectEmailImportSvc importSvc,
			final DashboardEventStream eventStream) {
		this.importSvc = importSvc;
		this.eventStream = eventStream;
	}

	@GetMapping("/import-architect-emails/status")
	public ImportStatus importStatus() {
		return new ImportStatus(importRunning.get());
	}

	@PostMapping("/import-architect-emails")
	public DevelopmentEmailImportResult importArchitectEmails() throws IOException {
		if (!importRunning.compareAndSet(false, true)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "An email import is already in progress");
		}
		eventStream.importStateChanged(true);
		try {
			return importSvc.importArchitectEmails();
		} finally {
			importRunning.set(false);
			eventStream.importStateChanged(false);
		}
	}

	public record ImportStatus(boolean running) {
	}
}
