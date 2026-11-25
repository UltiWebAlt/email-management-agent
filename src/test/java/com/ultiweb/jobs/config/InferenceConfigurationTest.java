package com.ultiweb.jobs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultiweb.jobs.EmailManagementAgent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

class InferenceConfigurationTest {
	private ApplicationContextRunner runner(final String apiKey) {
		return new ApplicationContextRunner()
				.withInitializer(context -> {
					context.getEnvironment().getPropertySources().remove("systemEnvironment");
					context.getEnvironment().getPropertySources().remove("systemProperties");
					if (apiKey != null) {
						context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testLiteLlmApiKey",
								Map.of("LITELLM_API_KEY", apiKey)));
					}
					new ConfigDataApplicationContextInitializer().initialize(context);
				})
				.withUserConfiguration(EmailManagementAgent.class)
				.withPropertyValues("spring.profiles.active=test,litellm",
						"GMAIL_CLIENT_ID=test-client", "GMAIL_CLIENT_SECRET=test-secret");
	}

	@Test
	void usesOneLiteLlmModelForSummariesAndClassification() {
		// given / when
		runner("test-litellm-key").run(context -> {
			// then
			assertThat(context).hasNotFailed().hasBean("summaryAiClient").hasBean("tagAiClient");
			assertThat(context).hasSingleBean(OpenAiChatModel.class);
			final OpenAiChatModel model = context.getBean(OpenAiChatModel.class);
			assertThat(model.getOptions().getModel()).isEqualTo("email-router");
			assertThat(context.getBean("summaryAiClient")).isNotSameAs(context.getBean("tagAiClient"));
			assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key")).isEqualTo("test-litellm-key");
			assertThat(context.getEnvironment().getProperty("spring.ai.openai.base-url"))
					.isEqualTo("http://localhost:4000");
		});
	}

	@Test
	void allowsOverridingTheRouterEndpointAndModel() {
		// given / when
		runner("test-litellm-key")
				.withPropertyValues("LITELLM_API_KEY=test-litellm-key", "LITELLM_MODEL=custom/router",
						"LITELLM_BASE_URL=http://litellm.internal:4000").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenAiChatModel.class).getOptions().getModel()).isEqualTo("custom/router");
			assertThat(context.getEnvironment().getProperty("spring.ai.openai.base-url"))
					.isEqualTo("http://litellm.internal:4000");
		});
	}

	@Test
	void acceptsABaseUrlThatAlreadyEndsInV1() {
		// given / when
		runner("test-litellm-key")
				.withPropertyValues("LITELLM_API_KEY=test-litellm-key",
						"LITELLM_BASE_URL=http://litellm.internal:4000/v1").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(EmailInferenceConfiguration.v1BaseUrl("http://litellm.internal:4000/v1"))
					.isEqualTo("http://litellm.internal:4000/v1");
			assertThat(EmailInferenceConfiguration.v1BaseUrl("http://litellm.internal:4000"))
					.isEqualTo("http://litellm.internal:4000/v1");
		});
	}

	@Test
	void rejectsClassificationConcurrencyAboveTheRouterLimit() {
		// given / when
		runner("test-litellm-key").withPropertyValues("LITELLM_MAX_CONCURRENT_REQUESTS=201").run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("between 1 and 200");
		});
	}

	@Test
	void rejectsSummaryConcurrencyAboveTheRouterLimit() {
		// given / when
		runner("test-litellm-key").withPropertyValues("LITELLM_SUMMARY_MAX_CONCURRENT_REQUESTS=0").run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("between 1 and 200");
		});
	}

	@Test
	void litellmRequiresItsApiKey() {
		// given / when
		runner(null).run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("LITELLM_API_KEY");
		});
	}
}
