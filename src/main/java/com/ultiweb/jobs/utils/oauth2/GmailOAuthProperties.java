package com.ultiweb.jobs.utils.oauth2;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;

import java.util.List;
import java.util.Locale;

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
		String clientSecret,
		List<String> accounts) {

	public GmailOAuthProperties {
		if (accounts == null || accounts.isEmpty()) {
			throw new IllegalArgumentException("Configure gmail.oauth.accounts with at least one Google account email address");
		}
		redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
		accounts = List.copyOf(accounts.stream().map(GmailOAuthProperties::normalizeAccount).distinct().toList());
	}

	static String normalizeAccount(final String account) {
		if (account == null || !account.trim().matches("[^\\s@,]+@[^\\s@,]+")) {
			throw new IllegalArgumentException("gmail.oauth.accounts must contain Google account email addresses");
		}
		return account.trim().toLowerCase(Locale.ROOT);
	}

	GoogleClientSecrets toClientSecrets() {
		final GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
				.setClientId(clientId)
				.setClientSecret(clientSecret)
				.setAuthUri(authUri)
				.setTokenUri(tokenUri)
				.setRedirectUris(redirectUris);
		return new GoogleClientSecrets().setInstalled(details);
	}
}
