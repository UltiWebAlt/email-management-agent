package com.ultiweb.jobs.svc.email;

import java.io.IOException;
import java.util.List;

public interface EmailReader {
	List<EmailMessage> readUnreadEmails() throws IOException;
}
