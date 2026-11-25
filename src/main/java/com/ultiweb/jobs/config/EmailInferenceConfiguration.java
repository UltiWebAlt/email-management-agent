package com.ultiweb.jobs.config;

import com.ultiweb.jobs.svc.ai.ConcurrencyLimitedEmailAiClient;
import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.ai.InferenceTraceEmailAiClient;
import com.ultiweb.jobs.svc.ai.SpringAiEmailClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds the single LiteLLM email-router model used by every inference service. Summary and
 * classifier clients share the model but keep separate concurrency limits.
 *
 * <p>"Share the model" is literal: both beans receive the same {@link OpenAiChatModel} instance,
 * so there is one HTTP client, one connection pool, and one set of retry settings for the whole
 * application. Only the {@link ConcurrencyLimitedEmailAiClient} wrappers differ, which is what
 * stops a burst of classifications from starving summaries. Reply generation reuses the
 * {@code summaryAiClient} bean rather than defining a third client.
 */
@Configuration(proxyBeanMethods = false)
public class EmailInferenceConfiguration {
	@Bean
	OpenAiChatModel chatModel(@Value("${spring.ai.openai.base-url}") final String baseUrl,
			@Value("${spring.ai.openai.api-key}") final String apiKey,
			@Value("${spring.ai.openai.chat.options.model}") final String modelName,
			@Value("${spring.ai.openai.chat.options.temperature:0.0}") final double temperature) {
		final var openAiClient = OpenAiSetup.setupSyncClient(v1BaseUrl(baseUrl), apiKey, null, null, null, null,
				false, false, modelName, Duration.ofSeconds(90), 2, null, Map.of(),
				ObservationRegistry.NOOP, null, List.of());
		return OpenAiChatModel.builder().openAiClient(openAiClient)
				.options(OpenAiChatOptions.builder().apiKey(apiKey).model(modelName).temperature(temperature).build()).build();
	}

	/** The OpenAI client appends /v1 itself, so the configured LiteLLM root has to end in /v1. */
	static String v1BaseUrl(final String baseUrl) {
		return baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/") ? baseUrl : baseUrl + "/v1";
	}

	@Bean("summaryAiClient")
	EmailAiClient summaryAiClient(final OpenAiChatModel chatModel,
			@Value("${email.inference.summary-max-concurrent-requests:8}") final int concurrency,
			@Value("${email.inference.log-prompts:false}") final boolean logPrompts) {
		return new ConcurrencyLimitedEmailAiClient(traced(new SpringAiEmailClient(ChatClient.create(chatModel)),
				"summary", logPrompts), concurrency);
	}

	@Bean("tagAiClient")
	EmailAiClient tagAiClient(final OpenAiChatModel chatModel,
			@Value("${email.inference.max-concurrent-requests:8}") final int concurrency,
			@Value("${email.inference.log-prompts:false}") final boolean logPrompts) {
		return new ConcurrencyLimitedEmailAiClient(traced(new SpringAiEmailClient(ChatClient.create(chatModel)),
				"classifier", logPrompts), concurrency);
	}

	private static EmailAiClient traced(final EmailAiClient client, final String purpose, final boolean logPrompts) {
		return logPrompts ? new InferenceTraceEmailAiClient(client, purpose) : client;
	}
}
