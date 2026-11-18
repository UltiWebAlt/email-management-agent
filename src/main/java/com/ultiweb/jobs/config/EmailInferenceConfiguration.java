package com.ultiweb.jobs.config;

import com.ultiweb.jobs.svc.ai.ConcurrencyLimitedEmailAiClient;
import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.ai.SpringAiEmailClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaChatProperties.class)
public class EmailInferenceConfiguration {
	private final Environment environment;

	EmailInferenceConfiguration(final Environment environment) {
		this.environment = environment;
	}

	@Bean
	OllamaChatModel ollamaChatModel(final OllamaApi api, final OllamaChatProperties properties) {
		return OllamaChatModel.builder().ollamaApi(api).options(properties.toOptions()).build();
	}

	@Bean
	@Profile("ollama & !lmstudio & !deepinfra-summary")
	EmailAiClient summaryAiClient(final OllamaChatModel model,
			@Value("${email.inference.local-max-concurrent-requests:2}") final int concurrency) {
		return new ConcurrencyLimitedEmailAiClient(new SpringAiEmailClient(ChatClient.create(model)), concurrency);
	}

	@Bean("summaryAiClient")
	@Profile("deepinfra-summary & !lmstudio")
	EmailAiClient deepInfraSummaryAiClient(@Qualifier("deepInfraSummaryChatModel") final OpenAiChatModel model,
			@Value("${email.inference.deepinfra-summary-max-concurrent-requests:8}") final int concurrency) {
		return new ConcurrencyLimitedEmailAiClient(new SpringAiEmailClient(ChatClient.create(model)), concurrency);
	}

	@Bean("summaryAiClient")
	@Profile("lmstudio")
	EmailAiClient lmStudioSummaryAiClient(@Qualifier("lmStudioSummaryChatModel") final OpenAiChatModel model,
			@Value("${email.inference.lmstudio-max-concurrent-requests:2}") final int concurrency,
			@Value("${email.inference.lmstudio-disable-thinking:true}") final boolean disableThinking) {
		final EmailAiClient delegate = new SpringAiEmailClient(ChatClient.create(model));
		final EmailAiClient summaryClient = disableThinking
				? (systemPrompt, userPrompt) -> delegate.complete("/no_think\n" + systemPrompt, userPrompt)
				: delegate;
		return new ConcurrencyLimitedEmailAiClient(summaryClient, concurrency);
	}

	@Bean("lmStudioSummaryChatModel")
	@Profile("lmstudio")
	OpenAiChatModel lmStudioSummaryChatModel(@Value("${lmstudio.base-url}") final String baseUrl,
			@Value("${lmstudio.api-key}") final String apiKey, @Value("${lmstudio.model}") final String modelName) {
		return openAiModel(baseUrl, apiKey, modelName, 0.0);
	}

	@Bean("deepInfraClassifierChatModel")
	OpenAiChatModel deepInfraClassifierChatModel(@Value("${spring.ai.openai.base-url}") final String baseUrl,
			@Value("${spring.ai.openai.chat.model}") final String modelName,
			@Value("${spring.ai.openai.chat.temperature:0.0}") final double temperature) {
		return openAiModel(baseUrl, environment.getRequiredProperty("DEEPINFRA_API_KEY"), modelName, temperature);
	}

	@Bean("deepInfraSummaryChatModel")
	@Profile("deepinfra-summary & !lmstudio")
	OpenAiChatModel deepInfraSummaryChatModel(@Value("${deepinfra.summary.base-url}") final String baseUrl,
			@Value("${deepinfra.summary.model}") final String modelName) {
		return openAiModel(baseUrl, environment.getRequiredProperty("DEEPINFRA_API_KEY"), modelName, 0.0);
	}

	@Bean("tagAiClient")
	EmailAiClient deepInfraTagAiClient(@Qualifier("deepInfraClassifierChatModel") final OpenAiChatModel model,
			@Value("${email.inference.deepinfra-max-concurrent-requests:8}") final int concurrency) {
		return new ConcurrencyLimitedEmailAiClient(new SpringAiEmailClient(ChatClient.create(model)), concurrency);
	}

	private static OpenAiChatModel openAiModel(final String baseUrl, final String apiKey,
			final String modelName, final double temperature) {
		final var openAiClient = OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null,
				false, false, modelName, Duration.ofSeconds(90), 2, null, Map.of(),
				ObservationRegistry.NOOP, null, List.of());
		return OpenAiChatModel.builder().openAiClient(openAiClient)
				.options(OpenAiChatOptions.builder().apiKey(apiKey).model(modelName).temperature(temperature).build()).build();
	}
}
