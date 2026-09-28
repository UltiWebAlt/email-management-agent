package com.ultiweb.jobs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultiweb.jobs.svc.EmailTriageSvc;
import com.ultiweb.jobs.svc.EmailPollingJob;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
class EmailPollingConfigurationTest {
	@Mock private EmailTriageSvc triageService;

	@Test
	void pollsAtStartupAndRepeatsEvenAfterAFailedPoll() throws Exception {
		// given
		final CountDownLatch polls = new CountDownLatch(2);
		when(triageService.processUnreadEmails()).thenAnswer(invocation -> {
			polls.countDown();
			if (polls.getCount() == 1) {
				throw new IOException("Temporary Gmail failure");
			}
			return List.of();
		});
		final ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(EmailPollingConfiguration.class)
				.withBean(EmailTriageSvc.class, () -> triageService)
				.withPropertyValues("gmail.polling.interval=50ms");

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasNotFailed().hasSingleBean(EmailPollingJob.class);
			assertThat(polls.await(3, TimeUnit.SECONDS)).isTrue();
			verify(triageService, atLeast(2)).processUnreadEmails();
		});
	}

	@Test
	void disabledPollingDoesNotStartMailboxOrInferenceWork() {
		// given
		final ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(EmailPollingConfiguration.class)
				.withBean(EmailTriageSvc.class, () -> triageService)
				.withPropertyValues("gmail.polling.enabled=false");

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasNotFailed().doesNotHaveBean(EmailPollingJob.class);
			verifyNoInteractions(triageService);
		});
	}
}
