package com.ultiweb.jobs.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Profiles;

class ProfileConfigurationTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(context -> {
				context.getEnvironment().getPropertySources().remove("systemEnvironment");
				context.getEnvironment().getPropertySources().remove("systemProperties");
				new ConfigDataApplicationContextInitializer().initialize(context);
			});

	@Test
	void developmentIsTheSafeDefault() {
		// given / when
		runner.run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("dev"))).isTrue();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("ollama"))).isTrue();
			assertThat(context.getEnvironment().getProperty("gmail.polling.enabled", Boolean.class)).isFalse();
			assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
					.isEqualTo("jdbc:sqlite:data/job-search-dev.db");
		});
	}

	@Test
	void productionUsesDeepInfraAndTheProductionDatabase() {
		// given / when
		runner.withPropertyValues("spring.profiles.active=production").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("production"))).isTrue();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("deepinfra"))).isTrue();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("ollama"))).isFalse();
			assertThat(context.getEnvironment().getProperty("gmail.polling.enabled", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
					.isEqualTo("jdbc:sqlite:data/job-search.db");
		});
	}

	@Test
	void localProductionUsesOllamaAndTheProductionDatabase() {
		// given / when
		runner.withPropertyValues("spring.profiles.active=local-production").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("local-production"))).isTrue();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("ollama"))).isTrue();
			assertThat(context.getEnvironment().acceptsProfiles(Profiles.of("deepinfra"))).isFalse();
			assertThat(context.getEnvironment().getProperty("gmail.polling.enabled", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
					.isEqualTo("jdbc:sqlite:data/job-search.db");
		});
	}

	@Test
	void testProfileUsesIsolatedPersistenceAndDisablesPolling() {
		// given / when
		runner.withPropertyValues("spring.profiles.active=test").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getProperty("gmail.polling.enabled", Boolean.class)).isFalse();
			assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
					.isEqualTo("jdbc:sqlite::memory:");
		});
	}
}
