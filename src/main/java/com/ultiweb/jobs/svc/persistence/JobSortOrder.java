package com.ultiweb.jobs.svc.persistence;

import java.util.Locale;

public enum JobSortOrder {
	NEWEST("COALESCE(p.source_received_at, p.created_at) DESC, p.id DESC"),
	OLDEST("COALESCE(p.source_received_at, p.created_at) ASC, p.id ASC"),
	TITLE_ASC("LOWER(COALESCE(p.title, '')) ASC, p.id DESC"),
	TITLE_DESC("LOWER(COALESCE(p.title, '')) DESC, p.id DESC"),
	COMPANY_ASC("LOWER(COALESCE(p.company, r.name, '')) ASC, p.id DESC"),
	COMPANY_DESC("LOWER(COALESCE(p.company, r.name, '')) DESC, p.id DESC");

	private final String sqlOrderBy;

	JobSortOrder(final String sqlOrderBy) {
		this.sqlOrderBy = sqlOrderBy;
	}

	public String sqlOrderBy() {
		return sqlOrderBy;
	}

	public static JobSortOrder fromValue(final String value) {
		if (value == null || value.isBlank()) {
			return NEWEST;
		}
		try {
			return valueOf(value.strip().toUpperCase(Locale.ROOT));
		} catch (final IllegalArgumentException ignored) {
			return NEWEST;
		}
	}
}
