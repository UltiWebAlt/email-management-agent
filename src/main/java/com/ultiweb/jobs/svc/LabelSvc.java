package com.ultiweb.jobs.svc;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Provides explicit access to labels in the authenticated Gmail account.
 */
@Service
public class LabelSvc {
	private static final String APPLICATION_NAME = "Email Management Agent";
	private static final String USER_ID = "me";
	private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

	private final GMailOAuth gmailOAuth;

	public LabelSvc(final GMailOAuth gmailOAuth) {
		this.gmailOAuth = gmailOAuth;
	}

	public List<Label> listLabels() throws IOException {
		final NetHttpTransport httpTransport = new NetHttpTransport.Builder().build();
		final Gmail service = new Gmail.Builder(httpTransport, JSON_FACTORY, gmailOAuth.authorize(httpTransport))
				.setApplicationName(APPLICATION_NAME)
				.build();
		final ListLabelsResponse listResponse = service.users().labels().list(USER_ID).execute();
		return listResponse.getLabels() == null ? List.of() : listResponse.getLabels();
	}
}
