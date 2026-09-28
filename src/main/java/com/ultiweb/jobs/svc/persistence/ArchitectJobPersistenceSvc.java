package com.ultiweb.jobs.svc.persistence;

import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.JobOpportunityDetails;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class ArchitectJobPersistenceSvc {
	private static final int MAX_TITLE_LENGTH = 255;

	private final ArchitectJobRepository repository;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Autowired
	public ArchitectJobPersistenceSvc(final ArchitectJobRepository repository,
			final ApplicationEventPublisher eventPublisher) {
		this(repository, eventPublisher, Clock.systemUTC());
	}

	ArchitectJobPersistenceSvc(final ArchitectJobRepository repository,
			final ApplicationEventPublisher eventPublisher, final Clock clock) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
		this.clock = clock;
	}

	ArchitectJobPersistenceSvc(final ArchitectJobRepository repository, final Clock clock) {
		this(repository, event -> { }, clock);
	}

	public boolean isPersisted(final EmailMessage email) {
		return repository.existsBySource(email.account(), email.id());
	}

	public boolean needsEnrichment(final EmailMessage email) {
		return repository.needsEnrichment(email.account(), email.id());
	}

	@Transactional
	public boolean persist(final EmailMessage email, final String summary) {
		return persist(email, summary, JobOpportunityDetails.empty());
	}

	@Transactional
	public boolean persist(final EmailMessage email, final String summary, final JobOpportunityDetails details) {
		Assert.hasText(summary, "An architect job summary is required");
		Assert.notNull(details, "Inferred architect job details are required");
		final ArchitectJobRecord record = record(email, summary, details, true);
		final boolean inserted = repository.saveIfAbsent(record);
		if (inserted) {
			eventPublisher.publishEvent(new ArchitectOpportunityAddedEvent());
		} else {
			repository.enrichExisting(record(email, summary, details, false));
			eventPublisher.publishEvent(new ArchitectOpportunityUpdatedEvent());
		}
		return inserted;
	}

	@Transactional
	public void enrichExisting(final EmailMessage email, final JobOpportunityDetails details) {
		repository.enrichExisting(record(email, "", details, false));
		eventPublisher.publishEvent(new ArchitectOpportunityUpdatedEvent());
	}

	private ArchitectJobRecord record(final EmailMessage email, final String summary,
			final JobOpportunityDetails details, final boolean fallbackTitle) {
		final RecruiterIdentity recruiter = recruiter(email);
		final Instant createdAt = clock.instant();
		return new ArchitectJobRecord(
				email.account(),
				email.id(),
				email.subject(),
				email.from(),
				email.receivedAt(),
				summary == null ? "" : summary.strip(),
				email.body(),
				email.htmlBody(),
				fallbackTitle ? firstNonBlank(details.title(), title(email.subject())) : blankToNull(details.title()),
				details.company(),
				details.location(),
				details.remote(),
				details.salaryRange(),
				details.requirements(),
				recruiter.email(),
				recruiter.name(),
				createdAt);
	}

	private static RecruiterIdentity recruiter(final EmailMessage email) {
		try {
			final InternetAddress[] addresses = InternetAddress.parseHeader(email.from(), false);
			if (addresses.length > 0 && addresses[0].getAddress() != null && !addresses[0].getAddress().isBlank()) {
				return new RecruiterIdentity(addresses[0].getAddress().strip().toLowerCase(Locale.ROOT),
						blankToNull(addresses[0].getPersonal()));
			}
		} catch (final AddressException ignored) {
			// Preserve the original sender on the position and use a stable placeholder for the required recruiter key.
		}
		final String sourceKey = (email.account() + "-" + email.id()).replaceAll("[^A-Za-z0-9]+", "-");
		return new RecruiterIdentity("unknown+" + sourceKey + "@invalid.local", null);
	}

	private static String title(final String subject) {
		if (subject == null || subject.isBlank()) {
			return "Architect opportunity";
		}
		final String normalized = subject.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return normalized.length() <= MAX_TITLE_LENGTH ? normalized : normalized.substring(0, MAX_TITLE_LENGTH);
	}

	private static String blankToNull(final String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String firstNonBlank(final String value, final String fallback) {
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private record RecruiterIdentity(String email, String name) {
	}
}
