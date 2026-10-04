package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailHtmlDisplayRendererTest {
	@Test
	void safelyRendersLegacyHtmlStoredInThePlainBodyColumn() {
		// given
		final String legacyHtml = "<html><body><p>Architect opening</p><ul><li>Design leadership</li></ul>"
				+ "<script>doNotRun()</script></body></html>";

		// when
		final String rendered = EmailHtmlDisplayRenderer.render(null, legacyHtml);

		// then
		assertTrue(rendered.contains("<p>Architect opening</p>"));
		assertTrue(rendered.contains("<li>Design leadership</li>"));
		assertFalse(rendered.contains("<script"));
		assertFalse(rendered.contains("doNotRun"));
	}

	@Test
	void decodesAndRendersEntityEscapedHtmlFromLegacyRecords() {
		// given
		final String escapedHtml = "&lt;p&gt;Role details&lt;/p&gt;";

		// when
		final String rendered = EmailHtmlDisplayRenderer.render(null, escapedHtml);

		// then
		assertTrue(rendered.contains("<p>Role details</p>"));
	}

	@Test
	void wrapsStoredPlainTextAsHtmlWithoutInterpretingMarkup() {
		// given
		final String plainText = "First line\nSecond line <script>literal text";

		// when
		final String rendered = EmailHtmlDisplayRenderer.render(null, plainText);

		// then
		assertTrue(rendered.contains("<br>"));
		assertTrue(rendered.contains("&lt;script&gt;"));
		assertFalse(rendered.contains("<script>"));
	}

	@Test
	void rendersExtractedLinkTextAsAnInlineClickableLink() {
		// given
		final String plainText = "Your job alert (https://www.linkedin.com/jobs/alerts)";

		// when
		final String rendered = EmailHtmlDisplayRenderer.render(null, plainText);

		// then
		assertTrue(rendered.contains("<a href=\"https://www.linkedin.com/jobs/alerts\""));
		assertTrue(rendered.contains(">Your job alert</a>"));
		assertFalse(rendered.contains("Your job alert (https://"));
	}
}
