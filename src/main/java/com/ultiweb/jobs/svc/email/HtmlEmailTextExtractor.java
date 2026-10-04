package com.ultiweb.jobs.svc.email;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

final class HtmlEmailTextExtractor {
	private static final Pattern PLAIN_TEXT_LINK = Pattern.compile("\\(https?://[^\\s)]++\\)");
	private static final Set<String> BLOCK_TAGS = Set.of(
			"address", "article", "aside", "blockquote", "div", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
			"header", "main", "nav", "p", "section");

	private HtmlEmailTextExtractor() {
		throw new UnsupportedOperationException("Utility class");
	}

	static String extract(final String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		final var document = Jsoup.parse(html);
		document.select("head, script, style, noscript, template, svg, canvas, [hidden], [aria-hidden=true]").remove();
		document.select("[style]").stream()
				.filter(element -> isHidden(element.attr("style")))
				.toList()
				.forEach(Element::remove);
		final var output = new StringBuilder();
		NodeTraversor.traverse(new SemanticTextVisitor(output), document.body());
		return normalize(output.toString());
	}

	static String sanitizeForDisplay(final String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		final var document = Jsoup.parseBodyFragment(html);
		document.select("head, script, style, noscript, template, iframe, object, embed, form, button, input, textarea, select, svg, canvas, video, audio, source, meta, link, base, [hidden], [aria-hidden=true]")
				.remove();
		document.select("[style]").removeAttr("style");
		document.select("img").forEach(image -> {
			final String alt = image.attr("alt").strip();
			if (alt.isBlank()) {
				image.remove();
			} else {
				image.replaceWith(new TextNode("[Image: " + alt + "]"));
			}
		});
		final Safelist safelist = Safelist.relaxed()
				.addTags("table", "thead", "tbody", "tfoot", "tr", "td", "th", "colgroup", "col")
				.addAttributes("td", "colspan", "rowspan")
				.addAttributes("th", "colspan", "rowspan")
				.addAttributes("a", "target", "rel");
		return Jsoup.clean(document.body().html(), "", safelist);
	}

	static String plainTextAsHtml(final String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		return java.util.Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').split("\\n{2,}"))
				.map(paragraph -> "<p>" + linkifyPlainText(paragraph).replace("\n", "<br>") + "</p>")
				.collect(java.util.stream.Collectors.joining());
	}

	private static String linkifyPlainText(final String text) {
		final Matcher matcher = PLAIN_TEXT_LINK.matcher(text);
		final StringBuilder html = new StringBuilder();
		int position = 0;
		while (matcher.find()) {
			int labelStart = matcher.start();
			while (labelStart > position && "\n()".indexOf(text.charAt(labelStart - 1)) < 0) {
				labelStart--;
			}
			html.append(escapeHtml(text.substring(position, labelStart)));
			final String label = text.substring(labelStart, matcher.start()).strip();
			final String url = matcher.group().substring(1, matcher.group().length() - 1);
			final String linkText = label.isBlank() ? url : label;
			html.append("<a href=\"").append(escapeHtml(url)).append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
					.append(escapeHtml(linkText)).append("</a>");
			position = matcher.end();
		}
		html.append(escapeHtml(text.substring(position)));
		return html.toString();
	}

	private static String escapeHtml(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static boolean isHidden(final String style) {
		final String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
		return normalized.contains("display:none") || normalized.contains("visibility:hidden");
	}

	private static String normalize(final String text) {
		return text.replace('\u00a0', ' ')
				.replaceAll("[\\t\\x0B\\f ]+", " ")
				.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"))
				.replaceAll("\\n{3,}", "\n\n")
				.strip();
	}

	private static final class SemanticTextVisitor implements NodeVisitor {
		private final StringBuilder output;

		private SemanticTextVisitor(final StringBuilder output) {
			this.output = output;
		}

		@Override
		public void head(final Node node, final int depth) {
			if (node instanceof TextNode textNode) {
				appendText(textNode.getWholeText());
				return;
			}
			if (!(node instanceof Element element)) {
				return;
			}
			final String tag = element.normalName();
			if (BLOCK_TAGS.contains(tag) || "tr".equals(tag)) {
				appendLineBreak();
			} else if ("br".equals(tag)) {
				appendLineBreak();
			} else if ("li".equals(tag)) {
				appendLineBreak();
				output.append("- ");
			} else if (("td".equals(tag) || "th".equals(tag)) && hasPreviousTableCell(element)) {
				appendInline(" | ");
			} else if ("img".equals(tag) && !element.attr("alt").isBlank()) {
				appendInline("[Image: " + element.attr("alt").strip() + "]");
			}
		}

		@Override
		public void tail(final Node node, final int depth) {
			if (!(node instanceof Element element)) {
				return;
			}
			final String tag = element.normalName();
			if ("a".equals(tag)) {
				appendLink(element);
			}
			if (BLOCK_TAGS.contains(tag) || "li".equals(tag) || "tr".equals(tag)) {
				appendLineBreak();
			}
		}

		private void appendText(final String text) {
			final String compact = text.replaceAll("\\s+", " ").strip();
			if (!compact.isEmpty()) {
				appendInline(compact);
			}
		}

		private void appendLink(final Element element) {
			final String href = semanticHref(element.attr("href"));
			final String label = element.text().strip();
			if (!href.isBlank() && !label.equalsIgnoreCase(href) && !label.equalsIgnoreCase(element.attr("href").strip())) {
				appendInline(" (" + href + ")");
			}
		}

		private void appendInline(final String text) {
			if (text.isBlank()) {
				return;
			}
			if (!output.isEmpty() && !Character.isWhitespace(output.charAt(output.length() - 1))
					&& !text.startsWith(" | ") && !text.startsWith(" (")) {
				output.append(' ');
			}
			output.append(text);
		}

		private void appendLineBreak() {
			if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
				output.append('\n');
			}
		}

		private static boolean hasPreviousTableCell(final Element element) {
			final Element previous = element.previousElementSibling();
			return previous != null && ("td".equals(previous.normalName()) || "th".equals(previous.normalName()));
		}

		private static String semanticHref(final String rawHref) {
			final String href = rawHref.strip();
			if (href.isEmpty() || href.startsWith("#") || href.regionMatches(true, 0, "javascript:", 0, 11)
					|| href.regionMatches(true, 0, "data:", 0, 5)) {
				return "";
			}
			try {
				final URI uri = new URI(href);
				if (uri.getScheme() == null) {
					return shorten(href);
				}
				if ("mailto".equalsIgnoreCase(uri.getScheme()) || "tel".equalsIgnoreCase(uri.getScheme())) {
					return shorten(uri.getScheme().toLowerCase(Locale.ROOT) + ":" + uri.getSchemeSpecificPart().split("\\?", 2)[0]);
				}
				if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
					return "";
				}
				return shorten(new URI(uri.getScheme().toLowerCase(Locale.ROOT), null, uri.getHost(), uri.getPort(),
						uri.getPath(), null, null).toString());
			} catch (final URISyntaxException exception) {
				return "";
			}
		}

		private static String shorten(final String value) {
			return value.length() <= 240 ? value : value.substring(0, 237) + "...";
		}
	}
}
