package com.ultiweb.jobs.svc;

import java.io.IOException;
import java.util.List;

public interface EmailReader {
	List<EmailMessage> readUnreadEmails() throws IOException;
}
