package com.ultiweb.jobs.svc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.ultiweb.jobs.EmailManagementAgent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class DeepInfraInferenceIntegrationTest {
	@Test
	@Timeout(30)
	void sendsConfiguredCredentialsModelAndPromptsToTheDeepInfraCompatibleEndpoint() throws Exception {
		// given
		final AtomicReference<String> requestPath = new AtomicReference<>();
		final AtomicReference<String> authorization = new AtomicReference<>();
		final AtomicReference<String> requestBody = new AtomicReference<>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requestPath.set(exchange.getRequestURI().getPath());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			final byte[] response = """
					{"id":"test-completion","object":"chat.completion","created":1,"model":"test/model",
					"choices":[{"index":0,"message":{"role":"assistant","content":"A test summary."},"finish_reason":"stop"}],
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
			new ApplicationContextRunner()
					.withInitializer(context -> {
						context.getEnvironment().getPropertySources().remove("systemEnvironment");
						context.getEnvironment().getPropertySources().remove("systemProperties");
						new ConfigDataApplicationContextInitializer().initialize(context);
					})
					.withUserConfiguration(EmailManagementAgent.class)
					.withPropertyValues("gmail.polling.enabled=false", "GMAIL_CLIENT_ID=test-client", "GMAIL_CLIENT_SECRET=test-secret",
							"DEEPINFRA_API_KEY=test-deepinfra-key", "DEEPINFRA_MODEL=test/model",
							"DEEPINFRA_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort() + "/v1/openai")
					.run(context -> {
						assertThat(context).hasNotFailed();
						// when
						final String result = context.getBean(EmailAiClient.class).complete("Summarize this email.", "Synthetic test email.");

						// then
						assertThat(result).isEqualTo("A test summary.");
						assertThat(requestPath.get()).isEqualTo("/v1/openai/chat/completions");
						assertThat(authorization.get()).isEqualTo("Bearer test-deepinfra-key");
						final var json = new ObjectMapper().readTree(requestBody.get());
						assertThat(json.get("model").asString()).isEqualTo("test/model");
						assertThat(json.get("messages").get(0).get("role").asString()).isEqualTo("system");
						assertThat(json.get("messages").get(0).get("content").asString()).isEqualTo("Summarize this email.");
						assertThat(json.get("messages").get(1).get("content").asString()).isEqualTo("Synthetic test email.");
					});
		} finally {
			server.stop(0);
		}
	}
}
