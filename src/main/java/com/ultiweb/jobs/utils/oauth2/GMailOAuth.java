package com.ultiweb.jobs.utils.oauth2;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.GmailScopes;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GMailOAuth {
private static final Logger logger = LoggerFactory.getLogger(GMailOAuth.class);
private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
private static final String TOKENS_DIRECTORY_PATH = "tokens";
private static final List<String> DEFAULT_SCOPES = List.of(GmailScopes.GMAIL_LABELS);

private final GmailOAuthProperties oauthProperties;

public GMailOAuth(GmailOAuthProperties oauthProperties) {
	this.oauthProperties = oauthProperties;
}

public Credential authorize(NetHttpTransport httpTransport) throws IOException {
	return authorize(httpTransport, DEFAULT_SCOPES, "user");
}

public Credential authorize(NetHttpTransport httpTransport, List<String> scopes) throws IOException {
	return authorize(httpTransport, scopes, userIdForScopes(scopes));
}

private Credential authorize(
		NetHttpTransport httpTransport, List<String> scopes, String userId) throws IOException {
	logger.info("Preparing Gmail OAuth authorization flow.");
	GoogleClientSecrets clientSecrets = oauthProperties.toClientSecrets();
	GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
			httpTransport, JSON_FACTORY, clientSecrets, scopes)
			.setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY_PATH)))
			.setAccessType("offline")
			.build();
	LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
	logger.info("Authorizing Gmail access through the local OAuth callback receiver on port 8888.");
	Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize(userId);
	logger.info("Gmail OAuth authorization completed.");
	return credential;
}

private static String userIdForScopes(List<String> scopes) {
	String scopeKey = String.join(" ", scopes.stream().sorted().toList());
	return "user-" + Integer.toHexString(scopeKey.hashCode());
}
}
