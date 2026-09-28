package com.ultiweb.jobs.svc.email;

import java.io.IOException;

public interface EmailLabelWriter {
	void applyLabel(String account, String messageId, String labelName) throws IOException;
}
