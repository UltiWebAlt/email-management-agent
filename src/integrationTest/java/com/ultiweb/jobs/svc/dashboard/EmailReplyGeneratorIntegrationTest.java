package com.ultiweb.jobs.svc.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.ai.SpringAiEmailClient;
import com.ultiweb.jobs.svc.persistence.ResponseCandidate;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.core.io.ByteArrayResource;

class EmailReplyGeneratorIntegrationTest {
	@Test
	@Timeout(15)
	void sendsRepliesThroughTheConfiguredLiteLlmRouter() throws Exception {
		// given
		final var authorizations = new CopyOnWriteArrayList<String>();
		final var requests = new CopyOnWriteArrayList<String>();
		final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			final byte[] response = """
					{"id":"reply-test","object":"chat.completion","created":1,"model":"email-router",
					"choices":[{"index":0,"message":{"role":"assistant","content":"Interested in the role."},"finish_reason":"stop"}],
					"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (final var body = exchange.getResponseBody()) {
				body.write(response);
			}
		});
		server.start();
		try {
			final EmailAiClient client = routerClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
					"test-litellm-key");
			final var generator = new EmailReplyGenerator(client,
					new ByteArrayResource("Java developer".getBytes(StandardCharsets.UTF_8)));
			final var candidate = new ResponseCandidate(7, 3, "owner@example.com", "Role", "Pat", "Original body",
					"Summary", "Architect", "pat@example.com", "Pat");

			// when / then
			assertThat(generator.generate(candidate)).isEqualTo("Interested in the role.");
			assertThat(generator.generate(candidate)).isEqualTo("Interested in the role.");
			assertThat(authorizations).containsExactly("Bearer test-litellm-key", "Bearer test-litellm-key");
			assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request)
					.contains("email-router", "Original body", "Java developer"));
		} finally {
			server.stop(0);
		}
	}

	private static EmailAiClient routerClient(final String baseUrl, final String apiKey) {
		final var openAiClient = OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null,
				false, false, "email-router", Duration.ofSeconds(90), 2, null, Map.of(),
				ObservationRegistry.NOOP, null, List.of());
		return new SpringAiEmailClient(ChatClient.create(OpenAiChatModel.builder().openAiClient(openAiClient)
				.options(OpenAiChatOptions.builder().apiKey(apiKey).model("email-router").build()).build()));
	}
}
