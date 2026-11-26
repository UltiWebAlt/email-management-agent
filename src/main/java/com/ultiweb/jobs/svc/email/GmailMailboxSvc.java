package com.ultiweb.jobs.svc.email;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
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
	/** Gmail's built-in inbox label, removed to archive a message. */
	private static final String INBOX_LABEL_ID = "INBOX";
	private static final int GMAIL_BATCH_SIZE = 20;
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
				"Reading architect-labeled messages for development import from account {}.", true);
	}

	/** Reads recent inbox mail for explicit development classification without modifying Gmail. */
	public List<EmailMessage> readRecentInboxEmailsForDevelopment() throws IOException {
		return readEmails("in:inbox newer_than:" + initialLookbackDays + "d",
				"Reading recent inbox messages for development import from account {}.", true);
	}

	private List<EmailMessage> readEmails(final String query, final String logMessage) throws IOException {
		return readEmails(query, logMessage, false);
	}

	private List<EmailMessage> readEmails(final String query, final String logMessage,
			final boolean failOnAccountError) throws IOException {
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
				if (failOnAccountError) {
					throw new IOException("Unable to read Gmail messages for development import", exception);
				}
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
		final List<String> messageIds = response.getMessages().stream().map(com.google.api.services.gmail.model.Message::getId).toList();
		final List<com.google.api.services.gmail.model.Message> messages = getMessagesInBatches(gmail, messageIds);
		return messages.stream().map(message -> toEmail(gmail, account, message)).toList();
	}

	private List<com.google.api.services.gmail.model.Message> getMessagesInBatches(final Gmail gmail,
			final List<String> messageIds) {
		final List<com.google.api.services.gmail.model.Message> messages = new ArrayList<>(messageIds.size());
		for (int start = 0; start < messageIds.size(); start += GMAIL_BATCH_SIZE) {
			final List<String> batchIds = messageIds.subList(start, Math.min(start + GMAIL_BATCH_SIZE, messageIds.size()));
			final List<com.google.api.services.gmail.model.Message> batchMessages = new ArrayList<>(batchIds.size());
			final List<String> errors = new ArrayList<>();
			final BatchRequest batch = gmail.batch();
			final JsonBatchCallback<com.google.api.services.gmail.model.Message> callback = new JsonBatchCallback<>() {
				@Override
				public void onSuccess(final com.google.api.services.gmail.model.Message message, final HttpHeaders responseHeaders) {
					batchMessages.add(message);
				}

				@Override
				public void onFailure(final GoogleJsonError error, final HttpHeaders responseHeaders) {
					errors.add(error.getMessage());
				}
			};
			try {
				for (final String messageId : batchIds) {
					gmail.users().messages().get(USER_ID, messageId).setFormat("full").queue(batch, callback);
				}
				batch.execute();
			} catch (final IOException exception) {
				throw new EmailTriageException("Unable to fetch Gmail messages in batch", exception);
			}
			if (!errors.isEmpty() || batchMessages.size() != batchIds.size()) {
				throw new EmailTriageException("Unable to fetch all Gmail messages in batch: " + errors,
						new IOException("Received " + batchMessages.size() + " of " + batchIds.size() + " messages"));
			}
			messages.addAll(batchMessages);
		}
		return messages;
	}

	@Override
	public void applyLabel(final String account, final String messageId, final String labelName) throws IOException {
		final Gmail gmail = gmail(account);
		final String labelId = findOrCreateLabel(gmail, labelName);
		gmail.users().messages().modify(USER_ID, messageId,
				new ModifyMessageRequest().setAddLabelIds(List.of(labelId))).execute();
	}

	@Override
	public void applyLabelAndArchive(final String account, final String messageId, final String labelName)
			throws IOException {
		final Gmail gmail = gmail(account);
		final String labelId = findOrCreateLabel(gmail, labelName);
		gmail.users().messages().modify(USER_ID, messageId, new ModifyMessageRequest()
				.setAddLabelIds(List.of(labelId))
				.setRemoveLabelIds(List.of(INBOX_LABEL_ID))).execute();
	}

	public void moveToTrash(final String account, final String messageId) throws IOException {
		gmail(account).users().messages().trash(USER_ID, messageId).execute();
	}

	Gmail gmail(final String account) throws IOException {
		return GmailServiceFactory.create(gmailOAuth, GMAIL_SCOPES, account);
	}

	private EmailMessage toEmail(final Gmail gmail, final String account,
			final com.google.api.services.gmail.model.Message message) {
		try {
			final MessagePart payload = message.getPayload();
			final Instant receivedAt = message.getInternalDate() == null ? null : Instant.ofEpochMilli(message.getInternalDate());
			final ExtractedEmailBody body = new GmailMessageBodyExtractor(gmail, message.getId()).extractContent(payload);
			return new EmailMessage(account, message.getId(), header(payload, "Subject"), header(payload, "From"),
					body.text(), receivedAt, body.html());
		} catch (final IOException | IllegalArgumentException exception) {
			throw new EmailTriageException("Unable to read message " + message.getId() + " for account " + account, exception);
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
