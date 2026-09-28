package com.ultiweb.jobs.svc.email;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.utils.email.GmailServiceFactory;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gmail API implementation for retrieving unread email and applying user labels.
 */
@Service
public final class GmailMailboxSvc implements EmailReader, EmailLabelWriter {
	private static final Logger LOGGER = LoggerFactory.getLogger(GmailMailboxSvc.class);
	private static final String USER_ID = "me";
	private static final List<String> GMAIL_SCOPES = List.of(GmailScopes.GMAIL_MODIFY);

	private final GMailOAuth gmailOAuth;
	private final int initialLookbackDays;

	public GmailMailboxSvc(final GMailOAuth gmailOAuth,
			@org.springframework.beans.factory.annotation.Value("${gmail.polling.initial-lookback-days:14}")
			final int initialLookbackDays) {
		if (initialLookbackDays < 1 || initialLookbackDays > 365) {
			throw new IllegalArgumentException("Initial Gmail lookback must be between 1 and 365 days");
		}
		this.gmailOAuth = gmailOAuth;
		this.initialLookbackDays = initialLookbackDays;
	}

	@Override
	public List<EmailMessage> readEmailsForTriage() throws IOException {
		return readEmails("in:inbox newer_than:" + initialLookbackDays + "d -label:" + EmailWorkflowLabels.PROCESSED,
				"Checking emails pending triage for account {}.");
	}

	@Override
	public List<EmailMessage> readArchitectEmailsPendingPersistence() throws IOException {
		return readEmails("label:" + EmailTag.ARCHITECT_JOBS.labelName()
				+ " -label:" + EmailWorkflowLabels.ARCHITECT_PERSISTED,
				"Checking manually tagged architect emails for account {}.");
	}

	/** Reads architect-labeled messages without changing Gmail labels, for explicit development imports. */
	public List<EmailMessage> readArchitectLabeledEmailsForDevelopment() throws IOException {
		return readEmails("label:" + EmailTag.ARCHITECT_JOBS.labelName(),
				"Reading architect-labeled messages for development import from account {}.");
	}

	private List<EmailMessage> readEmails(final String query, final String logMessage) throws IOException {
		final List<EmailMessage> emails = new ArrayList<>();
		for (final String account : gmailOAuth.accounts()) {
			LOGGER.info(logMessage, account);
			try {
				final Gmail gmail = gmail(account);
				final var request = gmail.users().messages().list(USER_ID).setQ(query);
				String nextPage;
				do {
					final ListMessagesResponse response = request.execute();
					emails.addAll(emailMessages(gmail, account, response));
					nextPage = response.getNextPageToken();
					request.setPageToken(nextPage);
				} while (nextPage != null && !nextPage.isBlank());
			} catch (final IOException | RuntimeException exception) {
				LOGGER.error("Unable to read account {}; continuing other accounts and retrying on the next poll.", account, exception);
			}
		}
		return List.copyOf(emails);
	}

	/**
	 * Returns up to maxResults newest messages per configured account without altering them.
	 */
	public List<EmailMessage> readLatestEmails(final int maxResults) throws IOException {
		if (maxResults < 1 || maxResults > 500) {
			throw new IllegalArgumentException("maxResults must be between 1 and 500");
		}
		final List<EmailMessage> emails = new ArrayList<>();
		for (final String account : gmailOAuth.accounts()) {
			final Gmail gmail = gmail(account);
			final ListMessagesResponse response = gmail.users().messages().list(USER_ID)
					.setMaxResults((long) maxResults)
					.execute();
			emails.addAll(emailMessages(gmail, account, response));
		}
		return List.copyOf(emails);
	}

	private List<EmailMessage> emailMessages(final Gmail gmail, final String account, final ListMessagesResponse response) {
		if (response.getMessages() == null) {
			return List.of();
		}
		return response.getMessages().stream()
				.map(message -> getEmail(gmail, account, message.getId()))
				.toList();
	}

	@Override
	public void applyLabel(final String account, final String messageId, final String labelName) throws IOException {
		final Gmail gmail = gmail(account);
		final String labelId = findOrCreateLabel(gmail, labelName);
		gmail.users().messages().modify(USER_ID, messageId,
				new ModifyMessageRequest().setAddLabelIds(List.of(labelId))).execute();
	}

	Gmail gmail(final String account) throws IOException {
		return GmailServiceFactory.create(gmailOAuth, GMAIL_SCOPES, account);
	}

	private EmailMessage getEmail(final Gmail gmail, final String account, final String messageId) {
		try {
			final com.google.api.services.gmail.model.Message message = gmail.users().messages().get(USER_ID, messageId)
					.setFormat("full")
					.execute();
			final MessagePart payload = message.getPayload();
			final Instant receivedAt = message.getInternalDate() == null ? null : Instant.ofEpochMilli(message.getInternalDate());
			return new EmailMessage(account, message.getId(), header(payload, "Subject"), header(payload, "From"),
					new GmailMessageBodyExtractor(gmail, messageId).extract(payload), receivedAt);
		} catch (final IOException | IllegalArgumentException exception) {
			throw new EmailTriageException("Unable to read message " + messageId + " for account " + account, exception);
		}
	}

	private String findOrCreateLabel(final Gmail gmail, final String labelName) throws IOException {
		final ListLabelsResponse response = gmail.users().labels().list(USER_ID).execute();
		final Optional<Label> existing = Optional.ofNullable(response.getLabels()).orElseGet(List::of).stream()
				.filter(label -> labelName.equals(label.getName()))
				.findFirst();
		if (existing.isPresent()) {
			return existing.get().getId();
		}
		return gmail.users().labels().create(USER_ID, new Label().setName(labelName)).execute().getId();
	}

	private static String header(final MessagePart payload, final String name) {
		return Optional.ofNullable(payload.getHeaders()).orElseGet(List::of).stream()
				.filter(header -> name.equalsIgnoreCase(header.getName()))
				.map(MessagePartHeader::getValue)
				.findFirst()
				.orElse("");
	}

}
