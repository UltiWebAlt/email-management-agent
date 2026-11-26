package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReplyOutputGuardTest {
	@Test
	void acceptsAnOrdinaryJobReply() {
		// given
		final String reply = "Hi Pat, thank you for reaching out about the Senior Architect role. "
				+ "I am interested and would welcome discussing the next steps.";

		// when / then
		assertDoesNotThrow(() -> ReplyOutputGuard.validate(reply, List.of("Pat <pat@example.com>")));
	}

	@Test
	void rejectsBlankReplies() {
		// given / when / then
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> ReplyOutputGuard.validate("   ", List.of()));
		assertEquals("The generated reply is empty", failure.getMessage());
	}

	@Test
	void rejectsLinksBecauseAJobReplyNeverNeedsOne() {
		// given / when / then
		assertThrows(IllegalStateException.class,
				() -> ReplyOutputGuard.validate("Please visit https://example.com to continue", List.of()));
	}

	@Test
	void rejectsEchoedContactDetails() {
		// given / when / then
		assertThrows(IllegalStateException.class, () -> ReplyOutputGuard.validate(
				"Send documents to pat@example.com for processing", List.of("pat@example.com")));
	}

	@Test
	void ignoresBlankContactDetails() {
		// given / when / then
		assertDoesNotThrow(() -> ReplyOutputGuard.validate("Thank you for your time.", List.of("", "  ")));
	}

	@Test
	void rejectsOverlongReplies() {
		// given
		final String reply = "a".repeat(4_001);

		// when / then
		assertThrows(IllegalStateException.class, () -> ReplyOutputGuard.validate(reply, List.of()));
	}
}
