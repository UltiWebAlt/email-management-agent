package com.ultiweb.jobs.svc.persistence;

public interface ArchitectJobRepository {
	boolean existsBySource(String account, String messageId);

	boolean saveIfAbsent(ArchitectJobRecord job);
}
