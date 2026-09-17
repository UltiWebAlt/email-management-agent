package com.ultiweb.jobs.utils.oauth2;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.GmailScopes;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GMailOAuth {
	private static final Logger LOGGER = LoggerFactory.getLogger(GMailOAuth.class);
	private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
	private static final List<String> DEFAULT_SCOPES = List.of(GmailScopes.GMAIL_LABELS);
	private static final String EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
	private final GmailOAuthProperties oauthProperties;

	public GMailOAuth(final GmailOAuthProperties oauthProperties) {
		this.oauthProperties = oauthProperties;
	}

	public List<String> accounts() {
		return oauthProperties.accounts();
	}

	public String singleAccount() {
		if (accounts().size() != 1) {
			throw new IllegalStateException("Multiple Gmail accounts are configured; select an account explicitly");
		}
		return accounts().getFirst();
	}

	public Credential authorize(final NetHttpTransport httpTransport) throws IOException {
		return authorize(httpTransport, DEFAULT_SCOPES, singleAccount());
	}

	public Credential authorize(final NetHttpTransport httpTransport, final List<String> scopes) throws IOException {
		return authorize(httpTransport, scopes, singleAccount());
	}

	public synchronized Credential authorize(final NetHttpTransport httpTransport, final List<String> scopes,
			final String requestedAccount) throws IOException {
		final String account = GmailOAuthProperties.normalizeAccount(requestedAccount);
		if (!accounts().contains(account)) {
			throw new IllegalArgumentException("Gmail account is not configured: " + account);
		}
		final List<String> requestedScopes = Stream.concat(scopes.stream(), Stream.of(EMAIL_SCOPE))
				.distinct().sorted().toList();
		final String tokenKey = tokenKey(account, requestedScopes);
		LOGGER.info("Preparing Gmail OAuth authorization for account {}.", account);
		final GoogleAuthorizationCodeFlow flow = createFlow(httpTransport, requestedScopes);
		final Credential credential = installedApp(flow, account).authorize(tokenKey);
		final String authenticatedAccount = authenticatedEmail(httpTransport, credential);
		if (!account.equalsIgnoreCase(authenticatedAccount)) {
			flow.getCredentialDataStore().delete(tokenKey);
			throw new IOException("Expected Gmail account " + account + " but authorized " + authenticatedAccount
					+ ". The mismatched token was removed; retry and sign in with " + account + ".");
		}
		LOGGER.info("Gmail OAuth credentials ready for account {}.", account);
		return credential;
	}

	GoogleAuthorizationCodeFlow createFlow(final NetHttpTransport httpTransport, final List<String> scopes) throws IOException {
		return new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, oauthProperties.toClientSecrets(), scopes)
				.setDataStoreFactory(new FileDataStoreFactory(new File("tokens")))
				.setAccessType("offline")
				.build();
	}

	AuthorizationCodeInstalledApp installedApp(final GoogleAuthorizationCodeFlow flow, final String account) {
		final LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(oauthProperties.callbackPort()).build();
		return new AccountAuthorizationCodeInstalledApp(flow, receiver, account, new AuthorizationCodeInstalledApp.DefaultBrowser());
	}

	String authenticatedEmail(final NetHttpTransport transport, final Credential credential) throws IOException {
		final var request = transport.createRequestFactory(credential)
				.buildGetRequest(new GenericUrl("https://www.googleapis.com/oauth2/v2/userinfo"));
		request.setParser(new JsonObjectParser(JSON_FACTORY));
		final var response = request.execute();
		try {
			final GenericJson profile = response.parseAs(GenericJson.class);
			return (String) profile.get("email");
		} finally {
			response.disconnect();
		}
	}

	static String tokenKey(final String account, final List<String> scopes) {
		final String scopeKey = String.join(" ", scopes.stream().distinct().sorted().toList());
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(scopeKey.getBytes(StandardCharsets.UTF_8));
			return GmailOAuthProperties.normalizeAccount(account) + ":" + HexFormat.of().formatHex(digest);
		} catch (final NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
