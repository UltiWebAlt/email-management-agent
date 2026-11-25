package com.ultiweb.jobs.svc.ai;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Spring AI adapter for the configured LiteLLM email-router chat model.
 */
public class SpringAiEmailClient implements EmailAiClient {
	private final ChatClient chatClient;

	public SpringAiEmailClient(final ChatClient chatClient) {
		this.chatClient = chatClient;
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
