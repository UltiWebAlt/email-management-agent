package com.ultiweb.jobs.svc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ConcurrencyLimitedEmailAiClientTest {
	@Test
	void concurrentVirtualThreadsNeverExceedTheLimitAndFailuresReleasePermits() throws Exception {
		// given
		final var active = new AtomicInteger();
		final var peak = new AtomicInteger();
		final var entered = new CountDownLatch(2);
		final var release = new CountDownLatch(1);
		final var client = new ConcurrencyLimitedEmailAiClient((system, user) -> {
			peak.accumulateAndGet(active.incrementAndGet(), Math::max);
			entered.countDown();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
				if ("fail".equals(user)) {
					throw new IllegalStateException("Synthetic failure");
				}
				return "Receipts";
			} catch (InterruptedException exception) {
				throw new AssertionError(exception);
			} finally {
				active.decrementAndGet();
			}
		}, 2);

		// when
		try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final var futures = new ArrayList<Future<String>>();
			for (int index = 0; index < 20; index++) {
				final String input = index == 0 ? "fail" : "ok";
				futures.add(executor.submit(() -> client.complete("", input)));
			}
			try {
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				assertEquals(2, active.get());
			} finally {
				release.countDown();
			}

			// then
			assertThrows(java.util.concurrent.ExecutionException.class, () -> futures.getFirst().get());
			for (final var future : futures.subList(1, futures.size())) {
				assertEquals("Receipts", future.get(5, TimeUnit.SECONDS));
			}
			assertEquals(2, peak.get());
			assertEquals(0, active.get());
		}
	}

	@Test
	void rejectsLimitsOutsidePublishedQuota() {
		// given / when / then
		for (final int limit : new int[] {0, -1, 201}) {
			assertThrows(IllegalArgumentException.class,
					() -> new ConcurrencyLimitedEmailAiClient((system, user) -> "", limit));
		}
	}
}
