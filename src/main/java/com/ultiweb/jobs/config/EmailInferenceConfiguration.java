package com.ultiweb.jobs.config;

import com.ultiweb.jobs.svc.ai.ConcurrencyLimitedEmailAiClient;
import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.ai.SpringAiEmailClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaChatProperties.class)
public class EmailInferenceConfiguration {
	@Bean
	OllamaChatModel ollamaChatModel(final OllamaApi api, final OllamaChatProperties properties) {
		return OllamaChatModel.builder().ollamaApi(api).options(properties.toOptions()).build();
	}

	@Bean
	EmailAiClient summaryAiClient(final OllamaChatModel model,
			@Value("${email.inference.local-max-concurrent-requests:2}") final int concurrency) {
		return new ConcurrencyLimitedEmailAiClient(new SpringAiEmailClient(ChatClient.create(model)), concurrency);
	}

	@Bean("tagAiClient")
	@Profile("deepinfra")
	EmailAiClient deepInfraTagAiClient(final OpenAiChatModel model,
			@Value("${email.inference.deepinfra-max-concurrent-requests:8}") final int concurrency) {
		return new ConcurrencyLimitedEmailAiClient(new SpringAiEmailClient(ChatClient.create(model)), concurrency);
	}

	@Bean("tagAiClient")
	@Profile("ollama")
	EmailAiClient ollamaTagAiClient(@Qualifier("summaryAiClient")
			final EmailAiClient summaryAiClient) {
		return summaryAiClient;
	}
}
