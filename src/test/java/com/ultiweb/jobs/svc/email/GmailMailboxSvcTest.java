package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.ultiweb.jobs.svc.ai.EmailTag;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailMailboxSvcTest {
	@Mock private GMailOAuth oauth;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail firstGmail;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail secondGmail;
	private GmailMailboxSvc service;

	@BeforeEach
	void setUp() {
		service = spy(new GmailMailboxSvc(oauth, 14));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void readsEachConfiguredMailboxAndPreservesAccountIdentity(final boolean latest) throws Exception {
		// given
		when(oauth.accounts()).thenReturn(List.of("first@example.com", "second@example.com"));
		doReturn(firstGmail).when(service).gmail("first@example.com");
		doReturn(secondGmail).when(service).gmail("second@example.com");
		for (final Gmail gmail : List.of(firstGmail, secondGmail)) {
			final var list = gmail.users().messages().list("me");
			if (latest) {
				when(list.setMaxResults(50L)).thenReturn(list);
			} else {
				when(list.setQ("in:inbox newer_than:14d -label:" + EmailWorkflowLabels.PROCESSED)).thenReturn(list);
			}
			when(list.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("same-id"))));
			when(gmail.users().messages().get("me", "same-id").setFormat("full").execute())
					.thenReturn(new Message().setId("same-id").setPayload(new MessagePart()));
		}

		// when
		final List<EmailMessage> emails = latest ? service.readLatestEmails(50) : service.readEmailsForTriage();

		// then
		assertEquals(List.of("first@example.com", "second@example.com"), emails.stream().map(EmailMessage::account).toList());
		assertEquals(List.of("same-id", "same-id"), emails.stream().map(EmailMessage::id).toList());
		for (final Gmail gmail : List.of(firstGmail, secondGmail)) {
			verify(gmail.users().messages().list("me")).execute();
		}
	}

	@Test
	void appliesLabelsOnlyInTheSelectedAccount() throws Exception {
		// given
		doReturn(secondGmail).when(service).gmail("second@example.com");
		when(secondGmail.users().labels().list("me").execute()).thenReturn(new ListLabelsResponse()
				.setLabels(List.of(new Label().setName("Dev_Jobs").setId("second-label"))));

		// when
		service.applyLabel("second@example.com", "same-id", "Dev_Jobs");

		// then
		verify(secondGmail.users().messages()).modify(eq("me"), eq("same-id"),
				argThat(request -> request.getAddLabelIds().equals(List.of("second-label"))));
		verify(secondGmail.users().messages().modify(eq("me"), eq("same-id"), any())).execute();
		verifyNoInteractions(firstGmail);
	}

	@Test
	void readsUnreadMessagesBeyondTheFirstPage() throws Exception {
		// given
		when(oauth.accounts()).thenReturn(List.of("first@example.com"));
		doReturn(firstGmail).when(service).gmail("first@example.com");
		final var request = firstGmail.users().messages().list("me");
		when(request.setQ("in:inbox newer_than:14d -label:" + EmailWorkflowLabels.PROCESSED)).thenReturn(request);
		when(request.execute())
				.thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("first"))).setNextPageToken("next-page"))
				.thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("second"))));
		for (final String id : List.of("first", "second")) {
			when(firstGmail.users().messages().get("me", id).setFormat("full").execute())
					.thenReturn(new Message().setId(id).setPayload(new MessagePart()));
		}

		// when
		final List<EmailMessage> messages = service.readEmailsForTriage();

		// then
		assertEquals(List.of("first", "second"), messages.stream().map(EmailMessage::id).toList());
		verify(request).setPageToken("next-page");
		verify(request, times(2)).execute();
	}

	@Test
	void aFailedAccountDoesNotPreventReadingOtherAccounts() throws Exception {
		// given
		when(oauth.accounts()).thenReturn(List.of("first@example.com", "second@example.com"));
		doThrow(new java.io.IOException("OAuth unavailable")).when(service).gmail("first@example.com");
		doReturn(secondGmail).when(service).gmail("second@example.com");
		final var request = secondGmail.users().messages().list("me");
		when(request.setQ("in:inbox newer_than:14d -label:" + EmailWorkflowLabels.PROCESSED)).thenReturn(request);
		when(request.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("id"))));
		when(secondGmail.users().messages().get("me", "id").setFormat("full").execute())
				.thenReturn(new Message().setId("id").setPayload(new MessagePart()));

		// when
		final List<EmailMessage> messages = service.readEmailsForTriage();

		// then
		assertEquals(1, messages.size());
		assertEquals("second@example.com", messages.getFirst().account());
	}

	@Test
	void readsArchitectLabelsFromAllHistoryUntilPersistenceMarkerIsApplied() throws Exception {
		// given
		when(oauth.accounts()).thenReturn(List.of("first@example.com"));
		doReturn(firstGmail).when(service).gmail("first@example.com");
		final var request = firstGmail.users().messages().list("me");
		when(request.setQ("label:" + EmailTag.ARCHITECT_JOBS.labelName()
				+ " -label:" + EmailWorkflowLabels.ARCHITECT_PERSISTED)).thenReturn(request);
		when(request.execute()).thenReturn(new ListMessagesResponse()
				.setMessages(List.of(new Message().setId("historical-id"))));
		when(firstGmail.users().messages().get("me", "historical-id").setFormat("full").execute())
				.thenReturn(new Message().setId("historical-id").setInternalDate(1_727_372_600_000L)
						.setPayload(new MessagePart()));

		// when
		final List<EmailMessage> messages = service.readArchitectEmailsPendingPersistence();

		// then
		assertEquals(List.of("historical-id"), messages.stream().map(EmailMessage::id).toList());
		assertEquals(java.time.Instant.ofEpochMilli(1_727_372_600_000L), messages.getFirst().receivedAt());
	}

	@Test
	void rejectsInvalidLookbackWindows() {
		// given / when / then
		for (final int days : new int[] {0, -1, 366}) {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
					() -> new GmailMailboxSvc(oauth, days));
		}
	}
}
