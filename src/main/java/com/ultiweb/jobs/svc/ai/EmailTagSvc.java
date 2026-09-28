package com.ultiweb.jobs.svc.ai;

import java.util.Optional;

public interface EmailTagSvc {
	Optional<EmailTag> suggestTag(String summary);
}
