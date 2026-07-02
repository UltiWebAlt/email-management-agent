package com.ultiweb.jobs.utils.email;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;

import java.io.IOException;
import java.util.List;

final class GmailServiceFactory {
private static final String APPLICATION_NAME = "Gmail samples";

private GmailServiceFactory() {
}

static Gmail create(GMailOAuth gmailOAuth, List<String> scopes) throws IOException {
	NetHttpTransport httpTransport = new NetHttpTransport.Builder().build();
	return new Gmail.Builder(
			httpTransport,
			GsonFactory.getDefaultInstance(),
			gmailOAuth.authorize(httpTransport, scopes))
			.setApplicationName(APPLICATION_NAME)
			.build();
}
}
