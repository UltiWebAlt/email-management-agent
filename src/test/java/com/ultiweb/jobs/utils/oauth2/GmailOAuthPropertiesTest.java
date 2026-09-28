package com.ultiweb.jobs.utils.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GmailOAuthPropertiesTest {
	@Test
	void bindsCommaSeparatedAccountsAndNormalizesDuplicates() {
		// given
		final Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
				"gmail.oauth.accounts", " First@Example.com ,second@example.com,first@example.com")));

		// when
		final GmailOAuthProperties properties = binder.bind("gmail.oauth", Bindable.of(GmailOAuthProperties.class)).get();

		// then
		assertEquals(List.of("first@example.com", "second@example.com"), properties.accounts());
	}

	@Test
	void rejectsMissingOrInvalidAccounts() {
		// given / when / then
		assertThrows(IllegalArgumentException.class, () -> properties(null));
		assertThrows(IllegalArgumentException.class, () -> properties(List.of()));
		assertThrows(IllegalArgumentException.class, () -> properties(List.of(" ")));
		assertThrows(IllegalArgumentException.class, () -> properties(List.of("not-an-email")));
		assertThrows(IllegalArgumentException.class, () -> properties(List.of("first@example.com", "")));
	}

	@Test
	void defensivelyCopiesListProperties() {
		// given
		final List<String> redirectUris = new ArrayList<>(List.of("http://localhost:8888/Callback"));
		final List<String> accounts = new ArrayList<>(List.of("First@Example.com"));

		// when
		final GmailOAuthProperties properties = new GmailOAuthProperties("project",
				"https://accounts.google.com/o/oauth2/auth", "https://oauth2.googleapis.com/token",
				"https://www.googleapis.com/oauth2/v1/certs", redirectUris, 8888,
				"test-client", "test-secret", accounts);
		redirectUris.clear();
		accounts.clear();

		// then
		assertEquals(List.of("http://localhost:8888/Callback"), properties.redirectUris());
		assertEquals(List.of("first@example.com"), properties.accounts());
		assertThrows(UnsupportedOperationException.class,
				() -> properties.redirectUris().add("http://localhost:9999/Callback"));
		assertThrows(UnsupportedOperationException.class,
				() -> properties.accounts().add("second@example.com"));
	}

	@Test
	void mapsOAuthConfigurationToInstalledApplicationSecrets() {
		// given
		final GmailOAuthProperties properties = properties(List.of("first@example.com"));

		// when
		final var secrets = properties.toClientSecrets();

		// then
		assertEquals("test-client", secrets.getInstalled().getClientId());
		assertEquals("test-secret", secrets.getInstalled().getClientSecret());
		assertEquals("https://accounts.google.com/o/oauth2/auth", secrets.getInstalled().getAuthUri());
		assertEquals("https://oauth2.googleapis.com/token", secrets.getInstalled().getTokenUri());
		assertEquals(List.of("http://localhost:8888/Callback"), secrets.getInstalled().getRedirectUris());
	}

	static GmailOAuthProperties properties(final List<String> accounts) {
		return new GmailOAuthProperties("project", "https://accounts.google.com/o/oauth2/auth",
				"https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v1/certs",
				List.of("http://localhost:8888/Callback"), 8888, "test-client", "test-secret", accounts);
	}
}
