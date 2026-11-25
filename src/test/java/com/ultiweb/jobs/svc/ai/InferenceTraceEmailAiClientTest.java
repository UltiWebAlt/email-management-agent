package com.ultiweb.jobs.svc.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class InferenceTraceEmailAiClientTest {
	@Test
	void delegatesAndReturnsTheDelegateResponse() {
		// given
		final EmailAiClient delegate = mock(EmailAiClient.class);
		when(delegate.complete("system", "user")).thenReturn("Dev_Jobs");
		final var client = new InferenceTraceEmailAiClient(delegate, "classifier");

		// when
		final String response = client.complete("system", "user");

		// then
		assertEquals("Dev_Jobs", response);
		verify(delegate).complete("system", "user");
	}

	@Test
	void logsAndRethrowsDelegateFailures() {
		// given
		final EmailAiClient delegate = mock(EmailAiClient.class);
		final var failure = new IllegalStateException("403: model not available");
		when(delegate.complete(anyString(), anyString())).thenThrow(failure);
		final var client = new InferenceTraceEmailAiClient(delegate, "summary");

		// when
		final var thrown = assertThrows(IllegalStateException.class, () -> client.complete("system", "user"));

		// then
		assertSame(failure, thrown);
	}

	@Test
	void toleratesNullResponsesAndPrompts() {
		// given
		final EmailAiClient delegate = (system, user) -> null;
		final var client = new InferenceTraceEmailAiClient(delegate, "summary");

		// when
		final String response = client.complete(null, null);

		// then
		assertEquals(null, response);
	}
}
