package com.ultiweb.jobs.utils.oauth2;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gmail.oauth")
public record GmailOAuthProperties(
		String projectId,
		String authUri,
		String tokenUri,
		String authProviderX509CertUrl,
		List<String> redirectUris,
		int callbackPort,
		String clientId,
		String clientSecret) {

GoogleClientSecrets toClientSecrets() {
	GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
			.setClientId(clientId)
			.setClientSecret(clientSecret)
			.setAuthUri(authUri)
			.setTokenUri(tokenUri)
			.setRedirectUris(redirectUris);
	return new GoogleClientSecrets().setInstalled(details);
}
}
