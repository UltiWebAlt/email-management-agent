package com.ultiweb.jobs.svc;

import java.util.Optional;

public interface EmailTagSvc {
	Optional<String> suggestTag(EmailMessage email, String summary);
}
