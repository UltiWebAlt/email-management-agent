package com.ultiweb.jobs.svc.email;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects common email HTML tags without a large backtracking alternation. */
final class EmailHtmlMarkup {
	private static final Pattern TAG = Pattern.compile("<\\s*+/?\\s*+([a-zA-Z][a-zA-Z0-9]*+)\\b[^<>]*+>");
	private static final Set<String> TAGS = Set.of("html", "head", "body", "a", "article", "blockquote", "br",
			"div", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "ol",
			"p", "section", "span", "table", "td", "th", "tr", "ul");

	private EmailHtmlMarkup() {
		throw new UnsupportedOperationException("Utility class");
	}

	static boolean containsMarkup(final String content) {
		final Matcher matcher = TAG.matcher(content);
		while (matcher.find()) {
			if (TAGS.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	static boolean startsWithMarkup(final String content) {
		final String stripped = content.stripLeading();
		if (stripped.startsWith("<!--") || stripped.regionMatches(true, 0, "<!doctype html", 0, 14)) {
			return true;
		}
		final Matcher matcher = TAG.matcher(stripped);
		return matcher.lookingAt() && TAGS.contains(matcher.group(1).toLowerCase(Locale.ROOT));
	}
}
