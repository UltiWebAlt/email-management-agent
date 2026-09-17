package com.ultiweb.jobs.utils.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AccountAuthorizationCodeInstalledAppTest {
	@Mock private AuthorizationCodeFlow flow;
	@Mock private VerificationCodeReceiver receiver;
	@Mock private AuthorizationCodeInstalledApp.Browser browser;

	@Test
	void identifiesTheAccountBeforeOpeningConsent(final CapturedOutput output) throws Exception {
		// given
		final var app = new AccountAuthorizationCodeInstalledApp(flow, receiver, "first@example.com", browser);
		final var url = new AuthorizationCodeRequestUrl("https://accounts.google.com/o/oauth2/auth", "client");
		doAnswer(invocation -> {
			assertTrue(output.getAll().contains("OAuth consent required for Gmail account first@example.com"));
			return null;
		}).when(browser).browse(anyString());

		// when
		app.onAuthorization(url);

		// then
		assertEquals("first@example.com", url.get("login_hint"));
		assertEquals("consent", url.get("prompt"));
		verify(browser).browse(url.build());
	}
}
