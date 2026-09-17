package com.ultiweb.jobs.svc;

import java.io.IOException;

public interface EmailLabelWriter {
	void applyLabel(String account, String messageId, String labelName) throws IOException;
}
