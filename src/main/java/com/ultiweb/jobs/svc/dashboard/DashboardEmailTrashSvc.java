package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import com.ultiweb.jobs.svc.persistence.JobDashboardRepository;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public final class DashboardEmailTrashSvc {
	private final JobDashboardRepository repository;
	private final GmailMailboxSvc mailbox;
	private final DashboardEventStream eventStream;

	public DashboardEmailTrashSvc(final JobDashboardRepository repository, final GmailMailboxSvc mailbox,
			final DashboardEventStream eventStream) {
		this.repository = repository;
		this.mailbox = mailbox;
		this.eventStream = eventStream;
	}

	public boolean moveToTrash(final long id) throws IOException {
		final var source = repository.findGmailSource(id)
				.orElseThrow(() -> new DashboardJobNotFoundException(id));
		if (!repository.reserveEmailTrash(id)) {
			return false;
		}
		try {
			mailbox.moveToTrash(source.account(), source.messageId());
			repository.dismiss(id);
		} finally {
			repository.releaseEmailTrash(id);
		}
		eventStream.opportunityRemoved();
		return true;
	}
}
