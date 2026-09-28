package com.ultiweb.jobs.svc.ai;

import java.util.concurrent.Semaphore;
import org.springframework.util.Assert;

/** Limits in-flight calls, including retries performed by the delegate. */
public final class ConcurrencyLimitedEmailAiClient implements EmailAiClient {
	private final EmailAiClient delegate;
	private final Semaphore permits;

	public ConcurrencyLimitedEmailAiClient(final EmailAiClient delegate, final int maxConcurrentRequests) {
		Assert.isTrue(maxConcurrentRequests >= 1 && maxConcurrentRequests <= 200,
				"Inference concurrency must be between 1 and 200");
		this.delegate = delegate;
		this.permits = new Semaphore(maxConcurrentRequests, true);
	}

	@Override
	public String complete(final String systemPrompt, final String userPrompt) {
		try {
			permits.acquire();
		} catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for inference capacity", exception);
		}
		try {
			return delegate.complete(systemPrompt, userPrompt);
		} finally {
			permits.release();
		}
	}
}
