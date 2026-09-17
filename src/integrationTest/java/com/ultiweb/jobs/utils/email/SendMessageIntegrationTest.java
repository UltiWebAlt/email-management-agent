package com.ultiweb.jobs.utils.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import com.ultiweb.jobs.utils.oauth2.GmailOAuthProperties;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class SendMessageIntegrationTest {

@Test
void sendEmailBuildsGmailSendRequestWithEncodedMessage() throws Exception {
	AtomicReference<String> requestUrl = new AtomicReference<>();
	AtomicReference<String> requestContent = new AtomicReference<>();
	MockHttpTransport transport = new MockHttpTransport() {
		@Override
		public MockLowLevelHttpRequest buildRequest(String method, String url) {
			assertEquals("POST", method);
			requestUrl.set(url);
			return new MockLowLevelHttpRequest() {
				@Override
				public MockLowLevelHttpResponse execute() throws IOException {
					requestContent.set(getContentAsString());
					return new MockLowLevelHttpResponse()
							.setStatusCode(200)
							.setContentType("application/json")
							.setContent("{\"id\":\"message-123\"}");
				}
			};
		}
	};

	Gmail service = new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), request -> {
	})
			.setApplicationName("Gmail samples")
			.build();

	Message result = SendMessage.sendEmail(
			service,
			"sender@example.com",
			"recipient@example.com");

	assertEquals("message-123", result.getId());
	assertNotNull(requestUrl.get());
	assertTrue(requestUrl.get().contains("/gmail/v1/users/me/messages/send"));
	assertNotNull(requestContent.get());
	assertTrue(requestContent.get().contains("\"raw\""));
	assertTrue(requestContent.get().contains("c2VuZGVyQGV4YW1wbGUuY29t"));
	assertTrue(requestContent.get().contains("cmVjaXBpZW50QGV4YW1wbGUuY29t"));
}

@Test
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "RUN_GMAIL_SEND_INTEGRATION_TEST", matches = "true")
void sendEmailSendsRealMessageFromRandallToRandall() throws Exception {
	String emailAddress = "randall.burgess@ultiweb.com";
	Message result = SendMessage.sendEmail(gmailOAuthFromEnvironment(), emailAddress, emailAddress);

	assertNotNull(result.getId());
}

private static GMailOAuth gmailOAuthFromEnvironment() {
	return new GMailOAuth(new GmailOAuthProperties(
			"cs-poc-dxk82jgpd30qog6ii1itvyo",
			"https://accounts.google.com/o/oauth2/auth",
			"https://oauth2.googleapis.com/token",
			"https://www.googleapis.com/oauth2/v1/certs",
			List.of("http://localhost:8888/Callback"),
			8888,
			requiredEnvironmentVariable("GMAIL_CLIENT_ID"),
			requiredEnvironmentVariable("GMAIL_CLIENT_SECRET"),
			List.of("randall.burgess@ultiweb.com")));
}

private static String requiredEnvironmentVariable(String name) {
	String value = System.getenv(name);
	assertNotNull(value, name + " must be set");
	return value;
}
}
