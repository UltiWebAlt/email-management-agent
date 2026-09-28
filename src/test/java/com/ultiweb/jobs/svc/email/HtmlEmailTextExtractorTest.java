package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtmlEmailTextExtractorTest {
	@Test
	void preservesVisibleStructureAndDestinationsWithoutMarkupNoise() {
		// given
		final String html = """
				<!doctype html>
				<html><head><style>.cta { color: red } .hidden { display: none }</style></head>
				<body>
					<h1>Weekly offers</h1>
					<p>Save on these plans:</p>
					<ul><li>Developer plan</li><li>Architect plan</li></ul>
					<table><tr><th>Plan</th><th>Price</th></tr><tr><td>Team</td><td>$20</td></tr></table>
					<a href="https://example.com/deals?campaign=private-tracking-value#cta">View offer</a>
					<div style="display: none">tracking words</div>
					<script>sendPrivateTrackingData()</script>
				</body></html>
				""";

		// when
		final String text = HtmlEmailTextExtractor.extract(html);

		// then
		assertTrue(text.contains("Weekly offers"));
		assertTrue(text.contains("- Developer plan\n- Architect plan"));
		assertTrue(text.contains("Plan | Price"));
		assertTrue(text.contains("Team | $20"));
		assertTrue(text.contains("View offer (https://example.com/deals)"));
		assertFalse(text.contains("<style"));
		assertFalse(text.contains("tracking words"));
		assertFalse(text.contains("private-tracking-value"));
		assertFalse(text.contains("sendPrivateTrackingData"));
	}

	@Test
	void keepsImageAlternativeTextForLinkedCallsToAction() {
		// given
		final String html = "<a href='https://example.com/register'><img alt='Register now'></a>";

		// when
		final String text = HtmlEmailTextExtractor.extract(html);

		// then
		assertTrue(text.contains("[Image: Register now]"));
		assertTrue(text.contains("https://example.com/register"));
	}
}
