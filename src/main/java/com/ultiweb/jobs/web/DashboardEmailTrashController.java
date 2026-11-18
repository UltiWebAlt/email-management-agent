package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DashboardEmailTrashSvc;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class DashboardEmailTrashController {
	private final DashboardEmailTrashSvc trashSvc;

	public DashboardEmailTrashController(final DashboardEmailTrashSvc trashSvc) {
		this.trashSvc = trashSvc;
	}

	@DeleteMapping("/api/dashboard/jobs/{id}/email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void moveToTrash(@PathVariable final long id) {
		try {
			if (!trashSvc.moveToTrash(id)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "A reply is selected or an action is already in progress");
			}
		} catch (final IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not move the source email to Gmail Trash", exception);
		}
	}
}
