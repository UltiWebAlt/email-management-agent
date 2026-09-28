package com.ultiweb.jobs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultiweb.jobs.EmailManagementAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InferenceConfigurationTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(context -> {
				context.getEnvironment().getPropertySources().remove("systemEnvironment");
				context.getEnvironment().getPropertySources().remove("systemProperties");
				new ConfigDataApplicationContextInitializer().initialize(context);
			})
			.withUserConfiguration(EmailManagementAgent.class)
			.withPropertyValues("gmail.polling.enabled=false", "GMAIL_CLIENT_ID=test-client", "GMAIL_CLIENT_SECRET=test-secret");

	@Test
	void usesLocalOllamaForSummariesAndDeepInfraForClassification() {
		// given / when
		runner.withPropertyValues("DEEPINFRA_API_KEY=test-deepinfra-key").run(context -> {
			// then
			assertThat(context).hasNotFailed().hasBean("summaryAiClient").hasBean("tagAiClient");
			assertThat(context).hasSingleBean(OpenAiChatModel.class).hasSingleBean(OllamaChatModel.class);
			assertThat(context.getBean(OllamaChatModel.class).getOptions().getModel()).isEqualTo("llama3.2:3b");
			assertThat(context.getBean("summaryAiClient")).isNotSameAs(context.getBean("tagAiClient"));
			assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key")).isEqualTo("test-deepinfra-key");
			assertThat(context.getEnvironment().getProperty("spring.ai.openai.base-url"))
					.isEqualTo("https://api.deepinfra.com/v1/openai");
			assertThat(context.getBean(OpenAiChatModel.class).getOptions().getModel())
					.isEqualTo("meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo");
		});
	}

	@Test
	void allowsOverridingTheDeepInfraModel() {
		// given / when
		runner.withPropertyValues("DEEPINFRA_API_KEY=test-deepinfra-key", "DEEPINFRA_MODEL=custom/model").run(context -> {
			// then
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenAiChatModel.class).getOptions().getModel()).isEqualTo("custom/model");
		});
	}

	@Test
	void ollamaWorksWithoutADeepInfraKeyAndRetainsItsSettings() {
		// given / when
		runner.withPropertyValues("spring.profiles.active=ollama", "OLLAMA_BASE_URL=http://localhost:11434",
				"OLLAMA_MODEL=llama3.2:3b").run(context -> {
			// then
			assertThat(context).hasNotFailed().hasBean("summaryAiClient").hasBean("tagAiClient");
			assertThat(context).hasSingleBean(OllamaChatModel.class).doesNotHaveBean(OpenAiChatModel.class);
			assertThat(context.getBean(OllamaChatModel.class).getOptions().getModel()).isEqualTo("llama3.2:3b");
		});
	}

	@Test
	void rejectsConcurrencyAboveThePublishedDeepInfraQuota() {
		// given / when
		runner.withPropertyValues("DEEPINFRA_API_KEY=test-key", "DEEPINFRA_MAX_CONCURRENT_REQUESTS=201").run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("between 1 and 200");
		});
	}

	@Test
	void deepInfraRequiresItsApiKey() {
		// given / when
		runner.run(context -> {
			// then
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("DEEPINFRA_API_KEY");
		});
	}
}
