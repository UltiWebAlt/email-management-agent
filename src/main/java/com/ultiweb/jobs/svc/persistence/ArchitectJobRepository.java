package com.ultiweb.jobs.svc.persistence;

public interface ArchitectJobRepository {
	boolean existsBySource(String account, String messageId);

	boolean saveIfAbsent(ArchitectJobRecord job);

	boolean needsEnrichment(String account, String messageId);

	void refreshEmailContent(String account, String messageId, String description, String htmlBody);

	void enrichExisting(ArchitectJobRecord job);
}
