package com.ultiweb.jobs.utils.email;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.util.List;

public final class GmailServiceFactory {
	private GmailServiceFactory() {
	}

	public static Gmail create(final GMailOAuth gmailOAuth, final List<String> scopes) throws IOException {
		return create(gmailOAuth, scopes, gmailOAuth.singleAccount());
	}

	public static Gmail create(final GMailOAuth gmailOAuth, final List<String> scopes, final String account) throws IOException {
		final NetHttpTransport httpTransport = new NetHttpTransport.Builder().build();
		return new Gmail.Builder(httpTransport, GsonFactory.getDefaultInstance(),
				gmailOAuth.authorize(httpTransport, scopes, account))
				.setApplicationName("Email Management Agent")
				.build();
	}
}
