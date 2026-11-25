package com.ultiweb.jobs.svc.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs every LiteLLM request and response so development runs show exactly what was sent for
 * evaluation. Enabled only through the {@code email.inference.log-prompts} property, which the dev
 * profile sets; the logs contain complete email bodies but omit the fixed system prompt, which is
 * identical on every call and only makes the trace hard to follow.
 */
public final class InferenceTraceEmailAiClient implements EmailAiClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(InferenceTraceEmailAiClient.class);
	private static final int MAX_LOGGED_CHARACTERS = 20_000;

	private final EmailAiClient delegate;
	private final String purpose;

	public InferenceTraceEmailAiClient(final EmailAiClient delegate, final String purpose) {
		this.delegate = delegate;
		this.purpose = purpose;
	}

	@Override
	public String complete(final String systemPrompt, final String userPrompt) {
		LOGGER.info("[{}] LiteLLM request: userPrompt=\"{}\"", purpose, bounded(userPrompt));
		try {
			final String response = delegate.complete(systemPrompt, userPrompt);
			LOGGER.info("[{}] LiteLLM response: \"{}\"", purpose, bounded(response));
			return response;
		} catch (final RuntimeException exception) {
			LOGGER.error("[{}] LiteLLM call failed: {}: {}", purpose, exception.getClass().getSimpleName(),
					exception.getMessage());
			throw exception;
		}
	}

	private static String bounded(final String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= MAX_LOGGED_CHARACTERS ? text
				: text.substring(0, MAX_LOGGED_CHARACTERS) + " [...truncated]";
	}
}
