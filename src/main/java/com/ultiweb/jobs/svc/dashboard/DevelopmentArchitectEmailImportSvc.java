package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import com.ultiweb.jobs.svc.persistence.ArchitectJobPersistenceSvc;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Explicitly copies real architect-labeled mail into the development database, without modifying Gmail. */
@Service
@Profile("dev")
public final class DevelopmentArchitectEmailImportSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentArchitectEmailImportSvc.class);
	private final GmailMailboxSvc gmailMailboxSvc;
	private final EmailSummarySvc emailSummarySvc;
	private final ArchitectJobPersistenceSvc persistenceSvc;

	public DevelopmentArchitectEmailImportSvc(final GmailMailboxSvc gmailMailboxSvc,
			final EmailSummarySvc emailSummarySvc, final ArchitectJobPersistenceSvc persistenceSvc) {
		this.gmailMailboxSvc = gmailMailboxSvc;
		this.emailSummarySvc = emailSummarySvc;
		this.persistenceSvc = persistenceSvc;
	}

	public DevelopmentEmailImportResult importArchitectEmails() throws IOException {
		final var emails = gmailMailboxSvc.readArchitectLabeledEmailsForDevelopment();
		int imported = 0;
		int skipped = 0;
		int failed = 0;
		for (final EmailMessage email : emails) {
			if (persistenceSvc.isPersisted(email)) {
				skipped++;
				continue;
			}
			try {
				final String summary = emailSummarySvc.summarize(email);
				if (persistenceSvc.persist(email, summary)) {
					imported++;
				} else {
					skipped++;
				}
			} catch (final RuntimeException exception) {
				failed++;
				LOGGER.warn("Development import failed for account={}, email id={} ({}).",
						email.account(), email.id(), exception.getClass().getSimpleName());
			}
		}
		LOGGER.info("Development Gmail import complete: scanned={}, imported={}, skipped={}, failed={}",
				emails.size(), imported, skipped, failed);
		return new DevelopmentEmailImportResult(emails.size(), imported, skipped, failed);
	}
}
