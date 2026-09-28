package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailMessageBodyExtractorTest {
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail gmail;

	@Test
	void extractsAnHtmlOnlyNewsletterAndBoundsThePromptText() throws Exception {
		// given
		final String html = "<html><head><style>" + ".unused{x:y}".repeat(10_000) + "</style></head><body>"
				+ "<h1>Product bulletin</h1>"
				+ "<p>A concise visible announcement.</p>"
				+ "<table><tr><td>Release</td><td>September</td></tr></table>"
				+ "<a href=\"https://example.com/read?tracking=" + "x".repeat(10_000) + "\">Read more</a>"
				+ "</body></html>";
		final MessagePart payload = textPart("text/html", html, StandardCharsets.UTF_8);

		// when
		final String body = new GmailMessageBodyExtractor(gmail, "message-id").extract(payload);

		// then
		assertTrue(body.contains("Product bulletin"));
		assertTrue(body.contains("Release | September"));
		assertTrue(body.contains("Read more (https://example.com/read)"));
		assertTrue(body.length() <= 5_000);
		assertFalse(body.contains("<html"));
		assertFalse(body.contains("unused"));
		assertFalse(body.contains("tracking="));
	}

	@Test
	void prefersRichHtmlInANestedAlternativeAndSkipsHtmlAttachments() throws Exception {
		// given
		final MessagePart plain = textPart("text/plain", "Fallback text without the destination.", StandardCharsets.UTF_8);
		final MessagePart html = textPart("text/html",
				"<p>Primary announcement</p><a href='https://example.com/action'>Take action</a>", StandardCharsets.UTF_8);
		final MessagePart alternative = new MessagePart().setMimeType("multipart/alternative").setParts(List.of(plain, html));
		final MessagePart attachment = textPart("text/html", "<p>Unrelated attached document</p>", StandardCharsets.UTF_8)
				.setHeaders(List.of(new MessagePartHeader().setName("Content-Disposition").setValue("attachment; filename=other.html")));
		final MessagePart payload = new MessagePart().setMimeType("multipart/mixed").setParts(List.of(alternative, attachment));

		// when
		final String body = new GmailMessageBodyExtractor(gmail, "message-id").extract(payload);

		// then
		assertTrue(body.contains("Primary announcement"));
		assertTrue(body.contains("Take action (https://example.com/action)"));
		assertFalse(body.contains("Fallback text"));
		assertFalse(body.contains("Unrelated attached document"));
	}

	@Test
	void retrievesASeparateGmailTextPartAndHonorsItsCharset() throws Exception {
		// given
		final Charset charset = Charset.forName("ISO-8859-1");
		final String html = "<p>Résumé de l'offre</p>";
		final MessagePart payload = new MessagePart()
				.setMimeType("text/html")
				.setHeaders(List.of(new MessagePartHeader().setName("Content-Type").setValue("text/html; charset=ISO-8859-1")))
				.setBody(new MessagePartBody().setAttachmentId("text-part-id"));
		when(gmail.users().messages().attachments().get("me", "message-id", "text-part-id").execute())
				.thenReturn(encodedBody(html, charset));

		// when
		final String body = new GmailMessageBodyExtractor(gmail, "message-id").extract(payload);

		// then
		assertTrue(body.contains("Résumé de l'offre"));
	}

	@Test
	void marksLongVisibleContentAsShortened() throws Exception {
		// given
		final String html = "<h1>Long newsletter</h1><p>" + "meaningful detail ".repeat(1_000) + "</p>";

		// when
		final String body = new GmailMessageBodyExtractor(gmail, "message-id")
				.extract(textPart("text/html", html, StandardCharsets.UTF_8));

		// then
		assertTrue(body.startsWith("Long newsletter"));
		assertTrue(body.endsWith("[Email body shortened for summarization]"));
		assertTrue(body.length() <= 5_000);
	}

	private static MessagePart textPart(final String mimeType, final String text, final Charset charset) {
		return new MessagePart()
				.setMimeType(mimeType)
				.setHeaders(List.of(new MessagePartHeader().setName("Content-Type")
						.setValue(mimeType + "; charset=" + charset.name())))
				.setBody(encodedBody(text, charset));
	}

	private static MessagePartBody encodedBody(final String text, final Charset charset) {
		return new MessagePartBody().setData(Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(charset)));
	}
}
