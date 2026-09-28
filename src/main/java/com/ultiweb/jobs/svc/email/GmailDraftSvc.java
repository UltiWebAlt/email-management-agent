package com.ultiweb.jobs.svc.email;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.email.CreateEmail;
import com.ultiweb.jobs.utils.email.CreateMessage;
import com.ultiweb.jobs.utils.email.GmailServiceFactory;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.util.List;
import javax.mail.MessagingException;
import org.springframework.stereotype.Service;

@Service
public class GmailDraftSvc {
	private final GMailOAuth gmailOAuth;

	public GmailDraftSvc(final GMailOAuth gmailOAuth) {
		this.gmailOAuth = gmailOAuth;
	}

	public String createDraft(final String account, final String recipient, final String subject, final String body)
			throws IOException, MessagingException {
		final Gmail gmail = GmailServiceFactory.create(gmailOAuth, List.of(GmailScopes.GMAIL_COMPOSE), account);
		final var mimeMessage = CreateEmail.createEmail(recipient, account, subject, body);
		final Message message = CreateMessage.createMessageWithEmail(mimeMessage);
		final Draft draft = gmail.users().drafts().create("me", new Draft().setMessage(message)).execute();
		if (draft == null || draft.getId() == null || draft.getId().isBlank()) {
			throw new IOException("Gmail returned no draft ID");
		}
		return draft.getId();
	}
}
