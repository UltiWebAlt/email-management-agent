package com.ultiweb.jobs.svc.dashboard;

import java.util.List;
import java.util.Locale;

/**
 * Post-generation checks on model-written email text before it is drafted or sent.
 *
 * <p>Instruction-level defenses cannot stop every prompt injection, so the reply path also enforces
 * properties that a legitimate job reply always has and an exfiltration attempt usually does not:
 * it stays short, contains no links, does not reproduce contact details taken from the source email,
 * and never mentions the model's own plumbing. Rejecting is deliberately cheaper than escalating:
 * a rejected reply fails that email and is retried on the next poll rather than being sent.
 */
final class ReplyOutputGuard {
	private static final int MAX_REPLY_CHARACTERS = 4_000;
	private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
			"http://", "https://", "www.", "```", "<email_content>", "</email_content>",
			"system prompt", "as an ai", "language model");

	private ReplyOutputGuard() {
		throw new UnsupportedOperationException("Utility class");
	}

	/**
	 * @param reply the generated reply body
	 * @param forbiddenContactDetails values taken from the source email that must not be echoed
	 * @throws IllegalStateException describing the first failed check
	 */
	static void validate(final String reply, final List<String> forbiddenContactDetails) {
		if (reply == null || reply.isBlank()) {
			throw new IllegalStateException("The generated reply is empty");
		}
		if (reply.length() > MAX_REPLY_CHARACTERS) {
			throw new IllegalStateException("The generated reply is too long to be a job response");
		}
		final String normalised = reply.toLowerCase(Locale.ROOT);
		for (final String fragment : FORBIDDEN_FRAGMENTS) {
			if (normalised.contains(fragment)) {
				throw new IllegalStateException("The generated reply contains disallowed content: " + fragment);
			}
		}
		for (final String detail : forbiddenContactDetails) {
			if (detail != null && !detail.isBlank() && normalised.contains(detail.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("The generated reply echoes contact details from the source email");
			}
		}
	}
}
