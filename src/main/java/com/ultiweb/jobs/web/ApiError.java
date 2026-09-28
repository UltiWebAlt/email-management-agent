package com.ultiweb.jobs.web;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String message) {
}
