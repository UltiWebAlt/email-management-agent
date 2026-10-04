package com.ultiweb.jobs.svc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a company named in a conventional “Company is hiring” subject or body line. */
public final class HiringCompanyExtractor {
	private static final Pattern HIRING_STATEMENT = Pattern.compile(
			"(?im)^\\s*([\\p{L}\\p{N}][\\p{L}\\p{N}&'’.,()/-]*(?:[ \\t]+[\\p{L}\\p{N}][\\p{L}\\p{N}&'’.,()/-]*){1,7})[ \\t]+is[ \\t]+hiring\\b");

	private HiringCompanyExtractor() {
		throw new UnsupportedOperationException("Utility class");
	}

	public static String extract(final String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		final Matcher matcher = HIRING_STATEMENT.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		final String company = stripTrailingPunctuation(matcher.group(1)).strip();
		return company.matches("(?i)(?:our company|our team|the company|the team|this company)")
				? null
				: company;
	}
	private static String stripTrailingPunctuation(final String value) {
		int end = value.length();
		while (end > 0 && ".,;:".indexOf(value.charAt(end - 1)) >= 0) {
			end--;
		}
		return value.substring(0, end);
	}

}
