package com.ultiweb.jobs.utils.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.AuthorizationCodeTokenRequest;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import java.awt.AWTError;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
	@Mock private AuthorizationCodeTokenRequest tokenRequest;
	@Mock private Credential credential;

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

	@Test
	void browserDisplayFailureStillWaitsForApprovalBeforeExchangingTheCode(final CapturedOutput output) throws Exception {
		// given
		final var app = new AccountAuthorizationCodeInstalledApp(flow, receiver, "first@example.com", browser);
		final var url = new AuthorizationCodeRequestUrl("https://accounts.google.com/o/oauth2/auth", "client");
		final String callback = "http://localhost:8888/Callback";
		final var response = new TokenResponse().setAccessToken("test-token");
		final CountDownLatch waitingForApproval = new CountDownLatch(1);
		final CountDownLatch approval = new CountDownLatch(1);
		when(receiver.getRedirectUri()).thenReturn(callback);
		when(flow.newAuthorizationUrl()).thenReturn(url);
		doThrow(new AWTError("Can't connect to X11 window server")).when(browser).browse(anyString());
		when(receiver.waitForCode()).thenAnswer(invocation -> {
			waitingForApproval.countDown();
			assertTrue(approval.await(5, TimeUnit.SECONDS), "Test approval was not delivered");
			return "approved-code";
		});
		when(flow.newTokenRequest("approved-code")).thenReturn(tokenRequest);
		when(tokenRequest.setRedirectUri(callback)).thenReturn(tokenRequest);
		when(tokenRequest.execute()).thenReturn(response);
		when(flow.createAndStoreCredential(response, "account-token-key")).thenReturn(credential);

		try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// when
			final var authorization = executor.submit(() -> app.authorize("account-token-key"));
			try {
				// then: browser failure does not complete or abort OAuth
				assertTrue(waitingForApproval.await(3, TimeUnit.SECONDS));
				assertFalse(authorization.isDone());
				verifyNoInteractions(tokenRequest);
				assertTrue(output.getAll().contains("Open this URL manually:"));
				assertTrue(output.getAll().contains("Waiting for OAuth approval for account first@example.com"));
				approval.countDown();
				assertSame(credential, authorization.get(3, TimeUnit.SECONDS));
				verify(receiver).stop();
			} finally {
				approval.countDown();
			}
		}
	}
}
