package com.ultiweb.jobs.svc.email;

import org.jsoup.parser.Parser;

/** Produces a sanitized HTML fragment for displaying stored original email content. */
public final class EmailHtmlDisplayRenderer {

	private EmailHtmlDisplayRenderer() {
		throw new UnsupportedOperationException("Utility class");
	}

	public static String render(final String htmlBody, final String plainText) {
		final String content = hasText(htmlBody) ? htmlBody : plainText;
		if (!hasText(content)) {
			return "";
		}
		if (containsMarkup(content)) {
			return HtmlEmailTextExtractor.sanitizeForDisplay(content);
		}
		final String decoded = Parser.unescapeEntities(content, false);
		if (containsMarkup(decoded)) {
			return HtmlEmailTextExtractor.sanitizeForDisplay(decoded);
		}
		return HtmlEmailTextExtractor.plainTextAsHtml(content);
	}

	private static boolean containsMarkup(final String content) {
		return EmailHtmlMarkup.containsMarkup(content);
	}

	private static boolean hasText(final String value) {
		return value != null && !value.isBlank();
	}
}
