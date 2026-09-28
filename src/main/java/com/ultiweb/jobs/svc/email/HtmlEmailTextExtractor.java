package com.ultiweb.jobs.svc.email;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

final class HtmlEmailTextExtractor {
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

	private static boolean isHidden(final String style) {
		final String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
		return normalized.contains("display:none") || normalized.contains("visibility:hidden");
	}

	private static String normalize(final String text) {
		return text.replace('\u00a0', ' ')
				.replaceAll("[\\t\\x0B\\f ]+", " ")
				.replaceAll(" *\\n *", "\n")
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
