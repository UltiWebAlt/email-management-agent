package com.ultiweb.jobs.svc;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.ultiweb.jobs.utils.email.GmailServiceFactory;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Provides explicit access to labels in the authenticated Gmail account.
 */
@Service
public class LabelSvc {
	private static final String USER_ID = "me";

	private final GMailOAuth gmailOAuth;

	public LabelSvc(final GMailOAuth gmailOAuth) {
		this.gmailOAuth = gmailOAuth;
	}

	public List<Label> listLabels() throws IOException {
		return listLabels(gmailOAuth.singleAccount());
	}

	public List<Label> listLabels(final String account) throws IOException {
		final Gmail service = GmailServiceFactory.create(gmailOAuth, List.of(GmailScopes.GMAIL_LABELS), account);
		final ListLabelsResponse listResponse = service.users().labels().list(USER_ID).execute();
		return listResponse.getLabels() == null ? List.of() : listResponse.getLabels();
	}
}
