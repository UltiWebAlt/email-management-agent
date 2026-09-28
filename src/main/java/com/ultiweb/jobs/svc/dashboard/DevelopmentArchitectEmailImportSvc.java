package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.ai.EmailSummarySvc;
import com.ultiweb.jobs.svc.ai.ArchitectJobDetailsInferenceSvc;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.svc.ai.EmailTagSvc;
import com.ultiweb.jobs.svc.JobOpportunityDetails;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import com.ultiweb.jobs.svc.persistence.ArchitectJobPersistenceSvc;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Explicitly copies real architect-labeled mail into the development database, without modifying Gmail. */
@Service
@Profile("dev")
public final class DevelopmentArchitectEmailImportSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentArchitectEmailImportSvc.class);
	private static final int MAX_ARCHITECT_JOBS_PER_IMPORT = 10;
	private final GmailMailboxSvc gmailMailboxSvc;
	private final EmailSummarySvc emailSummarySvc;
	private final EmailTagSvc emailTagSvc;
	private final ArchitectJobDetailsInferenceSvc jobDetailsInferenceSvc;
	private final ArchitectJobPersistenceSvc persistenceSvc;

	public DevelopmentArchitectEmailImportSvc(final GmailMailboxSvc gmailMailboxSvc,
			final EmailSummarySvc emailSummarySvc, final EmailTagSvc emailTagSvc,
			final ArchitectJobDetailsInferenceSvc jobDetailsInferenceSvc,
			final ArchitectJobPersistenceSvc persistenceSvc) {
		this.gmailMailboxSvc = gmailMailboxSvc;
		this.emailSummarySvc = emailSummarySvc;
		this.emailTagSvc = emailTagSvc;
		this.jobDetailsInferenceSvc = jobDetailsInferenceSvc;
		this.persistenceSvc = persistenceSvc;
	}

	public DevelopmentEmailImportResult importArchitectEmails() throws IOException {
		final var manuallyTagged = gmailMailboxSvc.readArchitectLabeledEmailsForDevelopment();
		final var recentInbox = gmailMailboxSvc.readRecentInboxEmailsForDevelopment();
		final Set<String> manuallyTaggedKeys = new LinkedHashSet<>();
		manuallyTagged.forEach(email -> manuallyTaggedKeys.add(key(email)));
		final Map<String, EmailMessage> candidates = new LinkedHashMap<>();
		manuallyTagged.forEach(email -> candidates.put(key(email), email));
		recentInbox.forEach(email -> candidates.putIfAbsent(key(email), email));
		final var emails = candidates.values();
		int imported = 0;
		int enriched = 0;
		int completedArchitectJobs = 0;
		int skipped = 0;
		int notArchitectJobs = 0;
		int failed = 0;
		int scanned = 0;
		for (final EmailMessage email : emails) {
			if (completedArchitectJobs >= MAX_ARCHITECT_JOBS_PER_IMPORT) {
				break;
			}
			scanned++;
			if (persistenceSvc.isPersisted(email)) {
				if (persistenceSvc.needsEnrichment(email)) {
					persistenceSvc.enrichExisting(email, inferJobDetails(email));
					enriched++;
					completedArchitectJobs++;
				} else {
					skipped++;
				}
				continue;
			}
			try {
				final String summary = emailSummarySvc.summarize(email);
				if (!manuallyTaggedKeys.contains(key(email))
						&& emailTagSvc.suggestTag(summary).filter(EmailTag.ARCHITECT_JOBS::equals).isEmpty()) {
					notArchitectJobs++;
					continue;
				}
				final JobOpportunityDetails details = inferJobDetails(email);
				if (persistenceSvc.persist(email, summary, details)) {
					imported++;
					completedArchitectJobs++;
				} else {
					skipped++;
				}
			} catch (final RuntimeException exception) {
				failed++;
				LOGGER.warn("Development import failed for account={}, email id={} ({}).",
						email.account(), email.id(), exception.getClass().getSimpleName());
			}
		}
		LOGGER.info("Development Gmail import complete: scanned={}, imported={}, enriched={}, skipped={}, nonArchitect={}, failed={}",
				scanned, imported, enriched, skipped, notArchitectJobs, failed);
		return new DevelopmentEmailImportResult(scanned, imported, enriched, skipped, notArchitectJobs, failed);
	}

	private JobOpportunityDetails inferJobDetails(final EmailMessage email) {
		try {
			return jobDetailsInferenceSvc.infer(email);
		} catch (final RuntimeException exception) {
			LOGGER.warn("Development job detail inference failed for account={}, email id={} ({}).",
					email.account(), email.id(), exception.getClass().getSimpleName());
			return JobOpportunityDetails.empty();
		}
	}

	private static String key(final EmailMessage email) {
		return email.account() + "\u0000" + email.id();
	}
}
