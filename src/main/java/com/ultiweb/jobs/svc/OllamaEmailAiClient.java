package com.ultiweb.jobs.svc;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Spring AI adapter for the configured Ollama chat model.
 */
@Service
public class OllamaEmailAiClient implements EmailAiClient {
	private final ChatClient chatClient;

	public OllamaEmailAiClient(final ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.build();
	}

	@Override
	public String complete(final String systemPrompt, final String userPrompt) {
		return chatClient.prompt()
				.system(systemPrompt)
				.user(userPrompt)
				.call()
				.content();
	}
}
