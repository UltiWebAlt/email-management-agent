package com.ultiweb.jobs.svc.email;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Thread;
import com.ultiweb.jobs.utils.email.CreateEmail;
import com.ultiweb.jobs.utils.email.CreateMessage;
import com.ultiweb.jobs.utils.email.GmailServiceFactory;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GmailDraftSvc {
	private static final Logger LOGGER = LoggerFactory.getLogger(GmailDraftSvc.class);
	private static final String METADATA_FORMAT = "metadata";
	private static final String MESSAGE_ID_HEADER = "Message-ID";
	private static final String REFERENCES_HEADER = "References";
	private static final String FOLLOW_UP_HEADER = "X-Email-Agent-Follow-Up";
	private static final String SEND_ID_HEADER = "X-Email-Agent-Response-ID";
	private final GmailFactory gmailFactory;

	@Autowired
	public GmailDraftSvc(final GMailOAuth gmailOAuth) {
		this((scopes, account) -> GmailServiceFactory.create(gmailOAuth, scopes, account));
	}

	GmailDraftSvc(final GmailFactory gmailFactory) {
		this.gmailFactory = gmailFactory;
	}

	public String createDraft(final String account, final String recipient, final String subject, final String body)
			throws IOException, MessagingException {
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_COMPOSE), account);
		final var mimeMessage = CreateEmail.createEmail(recipient, account, subject, body);
		final Message message = CreateMessage.createMessageWithEmail(mimeMessage);
		final Draft draft = gmail.users().drafts().create("me", new Draft().setMessage(message)).execute();
		if (draft == null || draft.getId() == null || draft.getId().isBlank()) {
			throw new IOException("Gmail returned no draft ID");
		}
		return draft.getId();
	}

	public GmailSentMessage sendReply(final String account, final String draftId, final String recipient,
			final String subject, final String body, final String labelName, final boolean redirectDraft)
			throws IOException, MessagingException {
		return sendReply(account, draftId, recipient, subject, body, labelName, redirectDraft, null);
	}

	public GmailSentMessage sendReply(final String account, final String draftId, final String recipient,
			final String subject, final String body, final String labelName, final boolean redirectDraft,
			final String sendIdentifier)
			throws IOException, MessagingException {
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_MODIFY), account);
		String effectiveDraftId = draftId;
		if (effectiveDraftId == null || effectiveDraftId.isBlank()) {
			final Draft created = gmail.users().drafts().create("me", draft(account, recipient, subject, body, sendIdentifier)).execute();
			if (created == null || created.getId() == null || created.getId().isBlank()) {
				throw new IOException("Gmail returned no draft ID");
			}
			effectiveDraftId = created.getId();
		} else if (redirectDraft || sendIdentifier != null) {
			gmail.users().drafts().update("me", effectiveDraftId,
					draft(account, recipient, subject, body, sendIdentifier)).execute();
		}
		final Message message = gmail.users().drafts().send("me", new Draft().setId(effectiveDraftId)).execute();
		if (message == null || message.getId() == null || message.getId().isBlank()) {
			throw new IOException("Gmail returned no sent message ID");
		}
		boolean labelApplied = labelName == null || labelName.isBlank();
		if (!labelApplied) {
			try {
				final String labelId = findOrCreateLabel(gmail, labelName);
				gmail.users().messages().modify("me", message.getId(),
						new ModifyMessageRequest().setAddLabelIds(List.of(labelId))).execute();
				labelApplied = true;
			} catch (final IOException exception) {
				LOGGER.error("Message {} was sent, but Gmail label '{}' could not be applied.", message.getId(), labelName, exception);
			}
		}
		return new GmailSentMessage(effectiveDraftId, message.getId(), message.getThreadId(),
				internalDate(message), labelApplied);
	}

	public Optional<GmailSentMessage> findSentReply(final String account, final String sendIdentifier,
			final String recipient) throws IOException {
		if (sendIdentifier == null || sendIdentifier.isBlank()) {
			return Optional.empty();
		}
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_MODIFY), account);
		String pageToken = "";
		do {
			final var request = gmail.users().messages().list("me")
					.setQ("in:sent rfc822msgid:" + sendIdentifier).setMaxResults(100L);
			if (!pageToken.isBlank()) {
				request.setPageToken(pageToken);
			}
			final ListMessagesResponse page = request.execute();
			if (page == null) {
				throw new IOException("Gmail returned no sent-message search result");
			}
			for (final Message listed : Optional.ofNullable(page.getMessages()).orElseGet(List::of)) {
				final Message message = gmail.users().messages().get("me", listed.getId())
						.setFormat(METADATA_FORMAT)
						.setMetadataHeaders(List.of(MESSAGE_ID_HEADER, "From", "To")).execute();
				if (message != null && message.getLabelIds() != null && message.getLabelIds().contains("SENT")
						&& sendIdentifier.equalsIgnoreCase(header(message, MESSAGE_ID_HEADER))
						&& addressMatches(header(message, "From"), account)
						&& addressMatches(header(message, "To"), recipient)) {
					return Optional.of(new GmailSentMessage(null, message.getId(), message.getThreadId(),
							internalDate(message), true));
				}
			}
			pageToken = Optional.ofNullable(page.getNextPageToken()).orElse("");
		} while (!pageToken.isBlank());
		return Optional.empty();
	}

	private static boolean addressMatches(final String header, final String expected) {
		try {
			for (final InternetAddress address : InternetAddress.parse(header, true)) {
				if (expected.equalsIgnoreCase(address.getAddress())) {
					return true;
				}
			}
		} catch (final javax.mail.internet.AddressException exception) {
			return false;
		}
		return false;
	}

	public Optional<GmailIncomingReply> findExternalReply(final String account, final String threadId,
			final Instant after) throws IOException {
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_MODIFY), account);
		final Thread thread = gmail.users().threads().get("me", threadId)
				.setFormat(METADATA_FORMAT).setMetadataHeaders(List.of("From")).execute();
		if (thread.getMessages() == null) {
			return Optional.empty();
		}
		return thread.getMessages().stream().filter(message -> {
			if (message.getInternalDate() == null || message.getInternalDate() <= after.toEpochMilli()
					|| message.getLabelIds() != null && message.getLabelIds().contains("SENT")) {
				return false;
			}
			final String from = header(message, "From");
			return !from.isBlank() && !from.toLowerCase(java.util.Locale.ROOT).contains(account.toLowerCase(java.util.Locale.ROOT));
		}).map(message -> new GmailIncomingReply(message.getId(), Instant.ofEpochMilli(message.getInternalDate())))
				.findFirst();
	}

	public GmailSentMessage sendFollowUp(final String account, final FollowUpEmail followUp, final String labelName)
			throws IOException, MessagingException {
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_MODIFY), account);
		final Message parent = gmail.users().messages().get("me", followUp.parentMessageId())
				.setFormat(METADATA_FORMAT).setMetadataHeaders(List.of(MESSAGE_ID_HEADER, REFERENCES_HEADER)).execute();
		final String rfcMessageId = header(parent, MESSAGE_ID_HEADER);
		if (rfcMessageId.isBlank()) {
			throw new IOException("Gmail did not return the parent RFC Message-ID");
		}
		final MimeMessage mimeMessage = CreateEmail.createEmail(followUp.recipient(), account, followUp.subject(), followUp.body());
		mimeMessage.setHeader("In-Reply-To", rfcMessageId);
		mimeMessage.setHeader(FOLLOW_UP_HEADER, Long.toString(followUp.positionId()));
		final String parentReferences = header(parent, REFERENCES_HEADER);
		mimeMessage.setHeader(REFERENCES_HEADER, parentReferences.isBlank()
				? rfcMessageId
				: parentReferences + " " + rfcMessageId);
		final Message message = CreateMessage.createMessageWithEmail(mimeMessage).setThreadId(followUp.threadId());
		final Message sent = gmail.users().messages().send("me", message).execute();
		if (sent == null || sent.getId() == null || sent.getId().isBlank()) {
			throw new IOException("Gmail returned no follow-up message ID");
		}
		boolean labelApplied = labelName == null || labelName.isBlank();
		if (!labelApplied) {
			try {
				final String labelId = findOrCreateLabel(gmail, labelName);
				gmail.users().messages().modify("me", sent.getId(),
						new ModifyMessageRequest().setAddLabelIds(List.of(labelId))).execute();
				labelApplied = true;
			} catch (final IOException exception) {
				LOGGER.error("Follow-up {} was sent, but Gmail label '{}' could not be applied.", sent.getId(), labelName, exception);
			}
		}
		return new GmailSentMessage(null, sent.getId(), sent.getThreadId(), internalDate(sent), labelApplied);
	}

	public java.util.Optional<GmailSentMessage> findExistingFollowUp(final String account, final String threadId,
			final long positionId) throws IOException {
		final Gmail gmail = gmailFactory.create(List.of(GmailScopes.GMAIL_MODIFY), account);
		final Thread thread = gmail.users().threads().get("me", threadId)
				.setFormat(METADATA_FORMAT).setMetadataHeaders(List.of(FOLLOW_UP_HEADER)).execute();
		if (thread.getMessages() == null) {
			return java.util.Optional.empty();
		}
		return thread.getMessages().stream()
				.filter(message -> Long.toString(positionId).equals(header(message, FOLLOW_UP_HEADER)))
				.findFirst()
				.map(message -> new GmailSentMessage(null, message.getId(), threadId,
						internalDate(message), true));
	}

	private static Draft draft(final String account, final String recipient, final String subject, final String body,
			final String sendIdentifier)
			throws IOException, MessagingException {
		final var mimeMessage = CreateEmail.createEmail(recipient, account, subject, body);
		if (sendIdentifier != null) {
			mimeMessage.saveChanges();
			mimeMessage.setHeader(MESSAGE_ID_HEADER, sendIdentifier);
			mimeMessage.setHeader(SEND_ID_HEADER, sendIdentifier);
		}
		final Message message = CreateMessage.createMessageWithEmail(mimeMessage);
		return new Draft().setMessage(message);
	}

	private static String findOrCreateLabel(final Gmail gmail, final String labelName) throws IOException {
		final ListLabelsResponse response = gmail.users().labels().list("me").execute();
		final Optional<Label> existing = Optional.ofNullable(response.getLabels()).orElseGet(List::of).stream()
				.filter(label -> labelName.equals(label.getName())).findFirst();
		if (existing.isPresent()) {
			return existing.get().getId();
		}
		return gmail.users().labels().create("me", new Label().setName(labelName)).execute().getId();
	}

	private static String header(final Message message, final String name) {
		return message.getPayload() == null || message.getPayload().getHeaders() == null ? ""
				: message.getPayload().getHeaders().stream()
						.filter(header -> name.equalsIgnoreCase(header.getName()))
						.map(MessagePartHeader::getValue).findFirst().orElse("");
	}

	private static Instant internalDate(final Message message) {
		return message.getInternalDate() == null ? null : Instant.ofEpochMilli(message.getInternalDate());
	}

	@FunctionalInterface
	interface GmailFactory {
		Gmail create(List<String> scopes, String account) throws IOException;
	}
}
