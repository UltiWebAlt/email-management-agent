package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DevelopmentArchitectEmailImportSvc;
import com.ultiweb.jobs.svc.dashboard.DevelopmentEmailImportResult;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/dev")
@Profile("dev")
public final class DevelopmentEmailImportController {
	private final DevelopmentArchitectEmailImportSvc importSvc;

	public DevelopmentEmailImportController(final DevelopmentArchitectEmailImportSvc importSvc) {
		this.importSvc = importSvc;
	}

	@PostMapping("/import-architect-emails")
	public DevelopmentEmailImportResult importArchitectEmails() throws IOException {
		return importSvc.importArchitectEmails();
	}
}
