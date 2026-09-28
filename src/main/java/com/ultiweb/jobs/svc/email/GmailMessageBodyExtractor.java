package com.ultiweb.jobs.svc.email;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class GmailMessageBodyExtractor {
	private static final int MAX_BODY_CHARACTERS = 5_000;
	private static final String TRUNCATION_MARKER = "\n\n[Email body shortened for summarization]";
	private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)");

	private final Gmail gmail;
	private final String messageId;

	GmailMessageBodyExtractor(final Gmail gmail, final String messageId) {
		this.gmail = gmail;
		this.messageId = messageId;
	}

	String extract(final MessagePart payload) throws IOException {
		return limit(content(payload).text());
	}

	private BodyContent content(final MessagePart part) throws IOException {
		if (part == null || isAttachment(part)) {
			return BodyContent.empty();
		}
		final String mimeType = mimeType(part);
		if ("text/plain".equals(mimeType)) {
			return new BodyContent(normalizePlainText(decode(part)), false);
		}
		if ("text/html".equals(mimeType)) {
			return new BodyContent(HtmlEmailTextExtractor.extract(decode(part)), true);
		}
		final List<MessagePart> parts = Optional.ofNullable(part.getParts()).orElseGet(List::of);
		if (!parts.isEmpty()) {
			final List<BodyContent> candidates = new ArrayList<>();
			for (final MessagePart child : parts) {
				final BodyContent candidate = content(child);
				if (!candidate.text().isBlank()) {
					candidates.add(candidate);
				}
			}
			if (mimeType.startsWith("multipart/alternative")) {
				return preferredAlternative(candidates);
			}
			return combine(candidates);
		}
		if ((mimeType.isBlank() || mimeType.startsWith("text/")) && hasBodyData(part.getBody())) {
			final String decoded = decode(part);
			return looksLikeHtml(decoded)
					? new BodyContent(HtmlEmailTextExtractor.extract(decoded), true)
					: new BodyContent(normalizePlainText(decoded), false);
		}
		return BodyContent.empty();
	}

	private String decode(final MessagePart part) throws IOException {
		final MessagePartBody body = part.getBody();
		if (body == null) {
			return "";
		}
		String data = body.getData();
		if ((data == null || data.isBlank()) && body.getAttachmentId() != null && !body.getAttachmentId().isBlank()) {
			final MessagePartBody attachment = gmail.users().messages().attachments()
					.get("me", messageId, body.getAttachmentId())
					.execute();
			data = attachment == null ? null : attachment.getData();
		}
		if (data == null || data.isBlank()) {
			return "";
		}
		return new String(Base64.getUrlDecoder().decode(data), charset(part));
	}

	private static Charset charset(final MessagePart part) {
		final String contentType = header(part, "Content-Type");
		final var matcher = CHARSET_PATTERN.matcher(contentType);
		if (matcher.find()) {
			try {
				return Charset.forName(matcher.group(1));
			} catch (final IllegalArgumentException ignored) {
				// Malformed or unsupported charsets are common in mail; UTF-8 is the safest fallback.
			}
		}
		return StandardCharsets.UTF_8;
	}

	private static BodyContent preferredAlternative(final List<BodyContent> candidates) {
		return candidates.reversed().stream()
				.filter(BodyContent::html)
				.findFirst()
				.orElseGet(() -> candidates.isEmpty() ? BodyContent.empty() : candidates.getLast());
	}

	private static BodyContent combine(final List<BodyContent> candidates) {
		final var output = new StringBuilder();
		final Set<String> included = new HashSet<>();
		boolean html = false;
		for (final BodyContent candidate : candidates) {
			final String text = candidate.text().strip();
			if (text.isEmpty() || !included.add(text)) {
				continue;
			}
			if (!output.isEmpty()) {
				output.append("\n\n");
			}
			output.append(text);
			html |= candidate.html();
		}
		return new BodyContent(output.toString(), html);
	}

	private static String normalizePlainText(final String text) {
		return text.replace("\r\n", "\n")
				.replace('\r', '\n')
				.replace('\u00a0', ' ')
				.replaceAll("[\\t\\x0B\\f ]+", " ")
				.replaceAll(" *\\n *", "\n")
				.replaceAll("\\n{3,}", "\n\n")
				.strip();
	}

	private static String limit(final String text) {
		final String normalized = text.strip();
		if (normalized.length() <= MAX_BODY_CHARACTERS) {
			return normalized;
		}
		final int preferredBoundary = normalized.lastIndexOf('\n', MAX_BODY_CHARACTERS - TRUNCATION_MARKER.length());
		final int minimumBoundary = MAX_BODY_CHARACTERS * 3 / 4;
		final int boundary = preferredBoundary >= minimumBoundary
				? preferredBoundary
				: MAX_BODY_CHARACTERS - TRUNCATION_MARKER.length();
		return normalized.substring(0, boundary).stripTrailing() + TRUNCATION_MARKER;
	}

	private static boolean isAttachment(final MessagePart part) {
		return header(part, "Content-Disposition").toLowerCase(Locale.ROOT).startsWith("attachment");
	}

	private static String mimeType(final MessagePart part) {
		return Optional.ofNullable(part.getMimeType()).orElse("").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
	}

	private static String header(final MessagePart part, final String name) {
		return Optional.ofNullable(part.getHeaders()).orElseGet(List::of).stream()
				.filter(header -> name.equalsIgnoreCase(header.getName()))
				.map(MessagePartHeader::getValue)
				.findFirst()
				.orElse("");
	}

	private static boolean hasBodyData(final MessagePartBody body) {
		return body != null && ((body.getData() != null && !body.getData().isBlank())
				|| (body.getAttachmentId() != null && !body.getAttachmentId().isBlank()));
	}

	private static boolean looksLikeHtml(final String value) {
		final String prefix = value.stripLeading().toLowerCase(Locale.ROOT);
		return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<body")
				|| prefix.startsWith("<table") || prefix.startsWith("<div");
	}

	private record BodyContent(String text, boolean html) {
		private static BodyContent empty() {
			return new BodyContent("", false);
		}
	}
}
