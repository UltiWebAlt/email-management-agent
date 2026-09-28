package com.ultiweb.jobs.svc.ai;

import com.ultiweb.jobs.svc.email.EmailMessage;

public interface EmailSummarySvc {
	String summarize(EmailMessage email);
}
