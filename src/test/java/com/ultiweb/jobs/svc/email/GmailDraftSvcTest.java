package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Thread;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class GmailDraftSvcTest {
	private static final String ACCOUNT = "sender@example.com";
	private final Gmail gmail = mock(Gmail.class, RETURNS_DEEP_STUBS);
	private final GmailDraftSvc.GmailFactory factory = mock(GmailDraftSvc.GmailFactory.class);
	private GmailDraftSvc service;

	@BeforeEach
	void setUp() throws IOException {
		service = new GmailDraftSvc(factory);
		when(factory.create(any(), eq(ACCOUNT))).thenReturn(gmail);
	}

	@Test
	void createsDraftAndReturnsItsId() throws Exception {
		// given
		when(gmail.users().drafts().create(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Draft().setId("draft-1"));

		// when
		final String draftId = service.createDraft(ACCOUNT, "to@example.com", "Subject", "Body");

		// then
		assertEquals("draft-1", draftId);
		verify(factory).create(List.of(GmailScopes.GMAIL_COMPOSE), ACCOUNT);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  "})
	void rejectsDraftResponsesWithoutIds(final String draftId) throws Exception {
		// given
		when(gmail.users().drafts().create(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Draft().setId(draftId));

		// when / then
		assertThrows(IOException.class,
				() -> service.createDraft(ACCOUNT, "to@example.com", "Subject", "Body"));
	}

	@Test
	void createsSendsAndLabelsANewReplyDraft() throws Exception {
		// given
		when(gmail.users().drafts().create(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Draft().setId("draft-2"));
		when(gmail.users().drafts().send(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Message().setId("sent-2").setThreadId("thread-2").setInternalDate(1_700_000_000_000L));
		when(gmail.users().labels().list("me").execute())
				.thenReturn(new ListLabelsResponse().setLabels(List.of(new Label().setName("Follow Up").setId("label-1"))));
		when(gmail.users().messages().modify(eq("me"), eq("sent-2"), any()).execute())
				.thenReturn(new Message());

		// when
		final GmailSentMessage sent = service.sendReply(ACCOUNT, null, "to@example.com", "Job", "Body", "Follow Up", false);

		// then
		assertEquals("draft-2", sent.draftId());
		assertEquals("sent-2", sent.messageId());
		assertEquals("thread-2", sent.threadId());
		assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), sent.sentAt());
		assertTrue(sent.labelApplied());
		verify(gmail.users().messages()).modify(eq("me"), eq("sent-2"), any());
	}

	@Test
	void redirectsExistingDraftAndCreatesMissingLabel() throws Exception {
		// given
		when(gmail.users().drafts().update(eq("me"), eq("draft-3"), any(Draft.class)).execute())
				.thenReturn(new Draft());
		when(gmail.users().drafts().send(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Message().setId("sent-3"));
		when(gmail.users().labels().list("me").execute()).thenReturn(new ListLabelsResponse().setLabels(List.of()));
		when(gmail.users().labels().create(eq("me"), any(Label.class)).execute())
				.thenReturn(new Label().setId("created-label"));
		when(gmail.users().messages().modify(eq("me"), eq("sent-3"), any()).execute())
				.thenReturn(new Message());

		// when
		final GmailSentMessage sent = service.sendReply(ACCOUNT, "draft-3", "to@example.com", "Re: Job", "Updated",
				"Follow Up", true);

		// then
		assertTrue(sent.labelApplied());
		verify(gmail.users().drafts()).update(eq("me"), eq("draft-3"), any(Draft.class));
		verify(gmail.users().labels()).create(eq("me"), argThatLabel("Follow Up"));
	}

	@Test
	void continuesAfterSentReplyCannotBeLabeled() throws Exception {
		// given
		when(gmail.users().drafts().send(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Message().setId("sent-4"));
		when(gmail.users().labels().list("me").execute()).thenReturn(new ListLabelsResponse().setLabels(List.of()));
		when(gmail.users().labels().create(eq("me"), any(Label.class)).execute())
				.thenReturn(new Label().setId("label-4"));
		when(gmail.users().messages().modify(eq("me"), eq("sent-4"), any()).execute())
				.thenThrow(new IOException("label service unavailable"));

		// when
		final GmailSentMessage sent = service.sendReply(ACCOUNT, "draft-4", "to@example.com", "Job", "Body",
				"Follow Up", false);

		// then
		assertEquals("sent-4", sent.messageId());
		assertFalse(sent.labelApplied());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  "})
	void rejectsASentReplyWithoutAMessageId(final String messageId) throws Exception {
		// given
		when(gmail.users().drafts().send(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Message().setId(messageId));

		// when / then
		assertThrows(IOException.class,
				() -> service.sendReply(ACCOUNT, "draft-5", "to@example.com", "Job", "Body", null, false));
	}

	@Test
	void embedsTheDurableIdentifierInTheDraftSentThroughGmail() throws Exception {
		// given
		when(gmail.users().drafts().send(eq("me"), any(Draft.class)).execute())
				.thenReturn(new Message().setId("sent-6"));

		// when
		service.sendReply(ACCOUNT, "draft-6", "to@example.com", "Re: Job", "Body", null, false,
				"<stable@example.invalid>");

		// then
		final var outgoing = org.mockito.ArgumentCaptor.forClass(Draft.class);
		verify(gmail.users().drafts()).update(eq("me"), eq("draft-6"), outgoing.capture());
		final var mime = new javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(new java.util.Properties()),
				new java.io.ByteArrayInputStream(Base64.getUrlDecoder().decode(outgoing.getValue().getMessage().getRaw())));
		assertEquals("<stable@example.invalid>", mime.getHeader("Message-ID", null));
		assertEquals("<stable@example.invalid>", mime.getHeader("X-Email-Agent-Response-ID", null));
		assertEquals("to@example.com", mime.getRecipients(javax.mail.Message.RecipientType.TO)[0].toString());
	}

	@Test
	void reconcilesOnlyVerifiedSentMessages() throws Exception {
		// given
		when(gmail.users().messages().list("me").setQ("in:sent rfc822msgid:<stable@example.invalid>")
				.setMaxResults(100L).execute()).thenReturn(new ListMessagesResponse().setMessages(List.of(
				new Message().setId("wrong"), new Message().setId("matching"))));
		when(gmail.users().messages().get("me", "wrong").setFormat("metadata")
				.setMetadataHeaders(List.of("Message-ID", "From", "To")).execute())
				.thenReturn(sentMetadata("wrong", "other@example.com"));
		when(gmail.users().messages().get("me", "matching").setFormat("metadata")
				.setMetadataHeaders(List.of("Message-ID", "From", "To")).execute())
				.thenReturn(sentMetadata("matching", "to@example.com"));

		// when
		final var found = service.findSentReply(ACCOUNT, "<stable@example.invalid>", "to@example.com");

		// then
		assertEquals("matching", found.orElseThrow().messageId());
		verify(factory).create(List.of(GmailScopes.GMAIL_MODIFY), ACCOUNT);
	}

	@Test
	void anEmptySentSearchDoesNotProveTheMessageWasNotSent() throws Exception {
		// given
		when(gmail.users().messages().list("me").setQ("in:sent rfc822msgid:<stable@example.invalid>")
				.setMaxResults(100L).execute()).thenReturn(new ListMessagesResponse());

		// when / then
		assertTrue(service.findSentReply(ACCOUNT, "<stable@example.invalid>", "to@example.com").isEmpty());
	}

	private static Message sentMetadata(final String id, final String recipient) {
		return new Message().setId(id).setThreadId("thread-6").setLabelIds(List.of("SENT"))
				.setPayload(new MessagePart().setHeaders(List.of(
						new MessagePartHeader().setName("Message-ID").setValue("<stable@example.invalid>"),
						new MessagePartHeader().setName("From").setValue("Sender <sender@example.com>"),
						new MessagePartHeader().setName("To").setValue("Recipient <" + recipient + ">"))));
	}

	@Test
	void findsAnExternalReplyAndIgnoresSentOrOlderMessages() throws Exception {
		// given
		final Instant after = Instant.ofEpochMilli(1_700_000_000_000L);
		final Thread thread = new Thread().setMessages(List.of(
				message("sent", after.toEpochMilli() + 1, "me@sender.example.com", "SENT"),
				message("old", after.toEpochMilli(), "recruiter@example.com", null),
				message("reply", after.toEpochMilli() + 2, "Recruiter@example.com", null)));
		when(gmail.users().threads().get("me", "thread-1").setFormat("metadata")
				.setMetadataHeaders(List.of("From")).execute()).thenReturn(thread);

		// when
		final Optional<GmailIncomingReply> reply = service.findExternalReply(ACCOUNT, "thread-1", after);

		// then
		assertTrue(reply.isPresent());
		assertEquals("reply", reply.orElseThrow().messageId());
		assertEquals(Instant.ofEpochMilli(after.toEpochMilli() + 2), reply.orElseThrow().receivedAt());
		verify(factory).create(List.of(GmailScopes.GMAIL_MODIFY), ACCOUNT);
	}

	@Test
	void ignoresMessagesFromTheAccountMessagesWithoutFromAndMessagesWithoutDates() throws Exception {
		// given
		final long after = 1_700_000_000_000L;
		final Thread thread = new Thread().setMessages(List.of(
				message("own", after + 1, "SENDER@example.com", null),
				new Message().setId("missing-from-date").setInternalDate(after + 1).setPayload(new MessagePart()),
				new Message().setId("missing-date").setPayload(new MessagePart().setHeaders(List.of(
						new MessagePartHeader().setName("From").setValue("recruiter@example.com"))))));
		when(gmail.users().threads().get("me", "thread-filtered").setFormat("metadata")
				.setMetadataHeaders(List.of("From")).execute()).thenReturn(thread);

		// when
		final Optional<GmailIncomingReply> reply = service.findExternalReply(ACCOUNT, "thread-filtered",
				Instant.ofEpochMilli(after));

		// then
		assertTrue(reply.isEmpty());
	}

	@Test
	void returnsEmptyWhenThreadHasNoMessagesAndFindsExistingFollowUpByMarker() throws Exception {
		// given
		when(gmail.users().threads().get("me", "thread-empty").setFormat("metadata")
				.setMetadataHeaders(List.of("From")).execute()).thenReturn(new Thread());
		when(gmail.users().threads().get("me", "thread-empty-follow-up").setFormat("metadata")
				.setMetadataHeaders(List.of("X-Email-Agent-Follow-Up")).execute()).thenReturn(new Thread());
		when(gmail.users().threads().get("me", "thread-existing").setFormat("metadata")
				.setMetadataHeaders(List.of("X-Email-Agent-Follow-Up")).execute())
				.thenReturn(new Thread().setMessages(List.of(new Message().setId("follow-up")
						.setInternalDate(1_700_000_000_000L).setPayload(new MessagePart().setHeaders(List.of(
							new MessagePartHeader().setName("X-Email-Agent-Follow-Up").setValue("42")))))));

		// when
		final Optional<GmailIncomingReply> noMessages = service.findExternalReply(ACCOUNT, "thread-empty", Instant.EPOCH);
		final Optional<GmailSentMessage> noFollowUpMessages = service.findExistingFollowUp(ACCOUNT,
				"thread-empty-follow-up", 42L);
		final Optional<GmailSentMessage> followUp = service.findExistingFollowUp(ACCOUNT, "thread-existing", 42L);

		// then
		assertTrue(noMessages.isEmpty());
		assertTrue(noFollowUpMessages.isEmpty());
		assertTrue(followUp.isPresent());
		assertEquals("follow-up", followUp.orElseThrow().messageId());
		assertTrue(followUp.orElseThrow().labelApplied());
	}

	@Test
	void returnsEmptyWhenThreadContainsNoFollowUpForThePosition() throws Exception {
		// given
		when(gmail.users().threads().get("me", "thread-2").setFormat("metadata")
				.setMetadataHeaders(List.of("X-Email-Agent-Follow-Up")).execute())
				.thenReturn(new Thread().setMessages(List.of(new Message().setId("other")
						.setPayload(new MessagePart().setHeaders(List.of(
							new MessagePartHeader().setName("X-Email-Agent-Follow-Up").setValue("7")))))));

		// when
		final Optional<GmailSentMessage> followUp = service.findExistingFollowUp(ACCOUNT, "thread-2", 42L);

		// then
		assertTrue(followUp.isEmpty());
	}

	@Test
	void sendsFollowUpInExistingThreadAndUsesParentReferences() throws Exception {
		// given
		when(gmail.users().messages().get("me", "parent").setFormat("metadata")
				.setMetadataHeaders(List.of("Message-ID", "References")).execute())
				.thenReturn(new Message().setPayload(new MessagePart().setHeaders(List.of(
						new MessagePartHeader().setName("Message-ID").setValue("<parent@example.com>"),
						new MessagePartHeader().setName("References").setValue("<earlier@example.com>")))));
		when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
				.thenReturn(new Message().setId("follow-up-sent").setThreadId("thread-3"));

		// when
		final GmailSentMessage sent = service.sendFollowUp(ACCOUNT,
				new FollowUpEmail("recruiter@example.com", "thread-3", 42L, "parent", "Re: Job", "Checking in"), null);

		// then
		assertEquals("follow-up-sent", sent.messageId());
		assertEquals("thread-3", sent.threadId());
		assertTrue(sent.labelApplied());
		final var outgoing = org.mockito.ArgumentCaptor.forClass(Message.class);
		verify(gmail.users().messages()).send(eq("me"), outgoing.capture());
		final var mime = new javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(new java.util.Properties()),
				new java.io.ByteArrayInputStream(java.util.Base64.getUrlDecoder().decode(outgoing.getValue().getRaw())));
		assertEquals("thread-3", outgoing.getValue().getThreadId());
		assertEquals("<parent@example.com>", mime.getHeader("In-Reply-To", null));
		assertEquals("<earlier@example.com> <parent@example.com>", mime.getHeader("References", null));
		assertEquals("42", mime.getHeader("X-Email-Agent-Follow-Up", null));
	}

	@Test
	void appliesFollowUpLabelAfterSending() throws Exception {
		// given
		stubParentWithMessageId();
		when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
				.thenReturn(new Message().setId("follow-up-labeled").setThreadId("thread-7"));
		when(gmail.users().labels().list("me").execute())
				.thenReturn(new ListLabelsResponse().setLabels(List.of(new Label().setName("Follow Up").setId("follow-up-label"))));
		when(gmail.users().messages().modify(eq("me"), eq("follow-up-labeled"), any()).execute())
				.thenReturn(new Message());

		// when
		final GmailSentMessage sent = service.sendFollowUp(ACCOUNT,
				new FollowUpEmail("recruiter@example.com", "thread-7", 7L, "parent", "Job", "Checking in"), "Follow Up");

		// then
		assertTrue(sent.labelApplied());
		verify(gmail.users().messages()).modify(eq("me"), eq("follow-up-labeled"), any());
	}

	@Test
	void reportsFollowUpWasSentWhenApplyingItsLabelFails() throws Exception {
		// given
		stubParentWithMessageId();
		when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
				.thenReturn(new Message().setId("follow-up-unlabeled"));
		when(gmail.users().labels().list("me").execute())
				.thenReturn(new ListLabelsResponse().setLabels(List.of()));
		when(gmail.users().labels().create(eq("me"), any(Label.class)).execute())
				.thenReturn(new Label().setId("new-label"));
		when(gmail.users().messages().modify(eq("me"), eq("follow-up-unlabeled"), any()).execute())
				.thenThrow(new IOException("label update failed"));

		// when
		final GmailSentMessage sent = service.sendFollowUp(ACCOUNT,
				new FollowUpEmail("recruiter@example.com", "thread-8", 8L, "parent", "Job", "Checking in"), "Follow Up");

		// then
		assertEquals("follow-up-unlabeled", sent.messageId());
		assertFalse(sent.labelApplied());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  "})
	void rejectsFollowUpResponsesWithoutAMessageId(final String messageId) throws Exception {
		// given
		stubParentWithMessageId();
		when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
				.thenReturn(new Message().setId(messageId));

		// when / then
		assertThrows(IOException.class, () -> service.sendFollowUp(ACCOUNT,
				new FollowUpEmail("to@example.com", "thread-9", 9L, "parent", "Job", "Body"), null));
	}

	@Test
	void rejectsFollowUpWhenParentHasNoRfcMessageId() throws Exception {
		// given
		when(gmail.users().messages().get("me", "parent").setFormat("metadata")
				.setMetadataHeaders(List.of("Message-ID", "References")).execute())
				.thenReturn(new Message().setPayload(new MessagePart()));

		// when / then
		assertThrows(IOException.class, () -> service.sendFollowUp(ACCOUNT,
				new FollowUpEmail("to@example.com", "thread-6", 6L, "parent", "Job", "Body"), null));
	}

	private static Message message(final String id, final long date, final String from, final String label) {
		return new Message().setId(id).setInternalDate(date).setLabelIds(label == null ? null : List.of(label))
				.setPayload(new MessagePart().setHeaders(List.of(new MessagePartHeader().setName("From").setValue(from))));
	}

	private void stubParentWithMessageId() throws IOException {
		when(gmail.users().messages().get("me", "parent").setFormat("metadata")
				.setMetadataHeaders(List.of("Message-ID", "References")).execute())
				.thenReturn(new Message().setPayload(new MessagePart().setHeaders(List.of(
						new MessagePartHeader().setName("Message-ID").setValue("<parent@example.com>")))));
	}

	private static Label argThatLabel(final String name) {
		return org.mockito.ArgumentMatchers.argThat(label -> name.equals(label.getName()));
	}

}
