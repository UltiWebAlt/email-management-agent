package com.ultiweb.jobs.svc.email;

import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

/** Produces a sanitized HTML fragment for displaying stored original email content. */
public final class EmailHtmlDisplayRenderer {
	private static final Pattern HTML_MARKUP_PATTERN = Pattern.compile(
			"(?is)<\\s*/?\\s*(?:html|head|body|a|article|blockquote|br|div|footer|h[1-6]|header|hr|li|main|ol|p|section|span|table|td|th|tr|ul)\\b[^>]*>");

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
		return HTML_MARKUP_PATTERN.matcher(content).find();
	}

	private static boolean hasText(final String value) {
		return value != null && !value.isBlank();
	}
}
