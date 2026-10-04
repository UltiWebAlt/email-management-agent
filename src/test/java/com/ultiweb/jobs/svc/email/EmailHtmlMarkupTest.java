package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailHtmlMarkupTest {
	@Test
	void recognizesMixedCaseTagsAfterLeadingWhitespace() {
		// given
		final String html = " \n\t<P class='intro'>Role details</P>";

		// when / then
		assertTrue(EmailHtmlMarkup.startsWithMarkup(html));
		assertTrue(EmailHtmlMarkup.containsMarkup(html));
	}

	@Test
	void distinguishesEmbeddedHtmlFromPlainTextAndIncompleteTags() {
		// given
		final String embedded = "Original message: <table><tr><td>Role</td></tr></table>";
		final String incomplete = "<".repeat(20_000) + "p";

		// when / then
		assertTrue(EmailHtmlMarkup.containsMarkup(embedded));
		assertFalse(EmailHtmlMarkup.startsWithMarkup(embedded));
		assertFalse(EmailHtmlMarkup.containsMarkup(incomplete));
		assertFalse(EmailHtmlMarkup.containsMarkup("Budget < 100 and <script>literal text"));
	}

	@Test
	void recognizesCommentAndDoctypePrefixes() {
		// given
		final String comment = " <!-- newsletter --> <p>Role</p>";
		final String doctype = "\n<!DOCTYPE html><html><body>Role</body></html>";

		// when / then
		assertTrue(EmailHtmlMarkup.startsWithMarkup(comment));
		assertTrue(EmailHtmlMarkup.startsWithMarkup(doctype));
	}
}
