package com.ultiweb.jobs.utils.oauth2;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import java.awt.AWTError;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AccountAuthorizationCodeInstalledApp extends AuthorizationCodeInstalledApp {
	private static final Logger LOGGER = LoggerFactory.getLogger(AccountAuthorizationCodeInstalledApp.class);
	private final String account;

	AccountAuthorizationCodeInstalledApp(final AuthorizationCodeFlow flow, final VerificationCodeReceiver receiver,
			final String account, final Browser browser) {
		super(flow, receiver, browser);
		this.account = account;
	}

	@Override
	protected void onAuthorization(final AuthorizationCodeRequestUrl authorizationUrl) throws IOException {
		LOGGER.info("OAuth consent required for Gmail account {}. Sign in with this account in the browser.", account);
		authorizationUrl.set("login_hint", account);
		authorizationUrl.set("prompt", "consent");
		try {
			super.onAuthorization(authorizationUrl);
		} catch (final AWTError | NoClassDefFoundError exception) {
			LOGGER.warn("Browser launch unavailable for account {} ({}). Open this URL manually: {}",
					account, exception.toString(), authorizationUrl.build());
		}
		LOGGER.info("Waiting for OAuth approval for account {} at {}. Complete consent in your browser to continue.",
				account, authorizationUrl.getRedirectUri());
	}
}
