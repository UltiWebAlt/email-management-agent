package com.ultiweb.jobs.svc;

import java.io.IOException;

public interface EmailLabelWriter {
	void applyLabel(String messageId, String labelName) throws IOException;
}
