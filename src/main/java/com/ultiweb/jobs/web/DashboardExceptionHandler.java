package com.ultiweb.jobs.web;

import com.ultiweb.jobs.svc.dashboard.DashboardJobNotFoundException;
import com.ultiweb.jobs.svc.dashboard.ResponseSelectionException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class DashboardExceptionHandler {
	@ExceptionHandler(DashboardJobNotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(final DashboardJobNotFoundException exception) {
		final HttpStatus status = HttpStatus.NOT_FOUND;
		return ResponseEntity.status(status)
				.body(new ApiError(Instant.now(), status.value(), exception.getMessage()));
	}

	@ExceptionHandler(ResponseSelectionException.class)
	public ResponseEntity<ApiError> handleBadRequest(final ResponseSelectionException exception) {
		final HttpStatus status = HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status)
				.body(new ApiError(Instant.now(), status.value(), exception.getMessage()));
	}
}
