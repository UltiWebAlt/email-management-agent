package com.ultiweb.jobs.utils.oauth2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import com.google.api.services.gmail.GmailScopes;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GMailOAuthTest {
	@Mock private GoogleAuthorizationCodeFlow flow;
	@Mock private VerificationCodeReceiver receiver;
	@Mock private AuthorizationCodeInstalledApp.Browser browser;
	@Mock private Credential firstCredential;
	@Mock private Credential secondCredential;
	@Mock private GoogleAuthorizationCodeTokenRequest tokenRequest;
	private final NetHttpTransport transport = new NetHttpTransport();
	private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_MODIFY);
	private static final List<String> IDENTITY_SCOPES = List.of(GmailScopes.GMAIL_MODIFY,
			"https://www.googleapis.com/auth/userinfo.email");

	@Test
	void reusesOnlyTheCachedCredentialForEachAccountWithoutOpeningTheBrowser() throws Exception {
		// given
		final GMailOAuth oauth = oauth();
		final String firstKey = GMailOAuth.tokenKey("first@example.com", IDENTITY_SCOPES);
		final String secondKey = GMailOAuth.tokenKey("second@example.com", IDENTITY_SCOPES);
		when(flow.loadCredential(firstKey)).thenReturn(firstCredential);
		when(flow.loadCredential(secondKey)).thenReturn(secondCredential);
		when(firstCredential.getRefreshToken()).thenReturn("first-refresh");
		when(secondCredential.getRefreshToken()).thenReturn("second-refresh");
		doReturn("first@example.com").when(oauth).authenticatedEmail(transport, firstCredential);
		doReturn("second@example.com").when(oauth).authenticatedEmail(transport, secondCredential);

		// when
		final Credential first = oauth.authorize(transport, SCOPES, " FIRST@example.com ");
		final Credential second = oauth.authorize(transport, SCOPES, "second@example.com");

		// then
		assertSame(firstCredential, first);
		assertSame(secondCredential, second);
		verify(flow).loadCredential(firstKey);
		verify(flow).loadCredential(secondKey);
		verifyNoInteractions(browser);
		verify(receiver, never()).getRedirectUri();
	}

	@ParameterizedTest
	@ValueSource(strings = {"first@example.com", "second@example.com"})
	void requestsConsentAndStoresNewCredentialsUnderTheRequestedAccount(final String account) throws Exception {
		// given
		final GMailOAuth oauth = oauth();
		final String key = GMailOAuth.tokenKey(account, IDENTITY_SCOPES);
		final String redirect = "http://localhost:8888/Callback";
		final var url = new GoogleAuthorizationCodeRequestUrl("client", redirect, IDENTITY_SCOPES);
		final var response = new GoogleTokenResponse().setAccessToken("new-access").setRefreshToken("new-refresh");
		when(receiver.getRedirectUri()).thenReturn(redirect);
		when(flow.newAuthorizationUrl()).thenReturn(url);
		when(receiver.waitForCode()).thenReturn("test-code");
		when(flow.newTokenRequest("test-code")).thenReturn(tokenRequest);
		when(tokenRequest.setRedirectUri(redirect)).thenReturn(tokenRequest);
		when(tokenRequest.execute()).thenReturn(response);
		when(flow.createAndStoreCredential(response, key)).thenReturn(firstCredential);
		doReturn(account).when(oauth).authenticatedEmail(transport, firstCredential);

		// when
		final Credential result = oauth.authorize(transport, SCOPES, account);

		// then
		assertSame(firstCredential, result);
		assertEquals(account, url.get("login_hint"));
		verify(browser).browse(url.build());
		verify(flow).createAndStoreCredential(response, key);
		verify(receiver).stop();
	}

	@Test
	void deletesOnlyTheMismatchedAccountsTokenAndRejectsAuthorization() throws Exception {
		// given
		final GMailOAuth oauth = oauth();
		final String key = GMailOAuth.tokenKey("first@example.com", IDENTITY_SCOPES);
		final var store = StoredCredential.getDefaultDataStore(new MemoryDataStoreFactory());
		store.set(key, new StoredCredential().setAccessToken("wrong-account-token"));
		store.set("other-account", new StoredCredential().setAccessToken("keep"));
		when(flow.getCredentialDataStore()).thenReturn(store);
		when(flow.loadCredential(key)).thenReturn(firstCredential);
		when(firstCredential.getRefreshToken()).thenReturn("refresh");
		doReturn("wrong@example.com").when(oauth).authenticatedEmail(transport, firstCredential);

		// when
		final IOException exception = assertThrows(IOException.class,
				() -> oauth.authorize(transport, SCOPES, "first@example.com"));

		// then
		assertTrue(exception.getMessage().contains("first@example.com"));
		assertTrue(exception.getMessage().contains("wrong@example.com"));
		assertFalse(store.containsKey(key));
		assertTrue(store.containsKey("other-account"));
	}

	@Test
	void rejectsAmbiguousAndUnconfiguredAccountsBeforeAccessingTokens() {
		// given
		final GMailOAuth oauth = new GMailOAuth(GmailOAuthPropertiesTest.properties(
				List.of("first@example.com", "second@example.com")));

		// when / then
		assertThrows(IllegalStateException.class, () -> oauth.authorize(transport, SCOPES));
		assertThrows(IllegalArgumentException.class, () -> oauth.authorize(transport, SCOPES, "other@example.com"));
	}

	@Test
	void tokenKeysSeparateAccountsAndPermissionsAndIgnoreScopeOrder() {
		// given
		final String key = GMailOAuth.tokenKey("first@example.com", List.of("scope-a", "scope-b"));

		// when / then
		assertEquals(key, GMailOAuth.tokenKey(" FIRST@example.com ", List.of("scope-b", "scope-a", "scope-a")));
		assertNotEquals(key, GMailOAuth.tokenKey("second@example.com", List.of("scope-a", "scope-b")));
		assertNotEquals(key, GMailOAuth.tokenKey("first@example.com", List.of("scope-a")));
	}

	private GMailOAuth oauth() throws IOException {
		final GMailOAuth oauth = spy(new GMailOAuth(GmailOAuthPropertiesTest.properties(
				List.of("first@example.com", "second@example.com"))));
		doReturn(flow).when(oauth).createFlow(eq(transport), eq(IDENTITY_SCOPES));
		doAnswer(invocation -> new AccountAuthorizationCodeInstalledApp(flow, receiver, invocation.getArgument(1), browser))
				.when(oauth).installedApp(eq(flow), anyString());
		return oauth;
	}
}
