package com.ultiweb.jobs.svc;

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
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailMailboxSvcTest {
	@Mock private GMailOAuth oauth;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail firstGmail;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail secondGmail;
	@Spy @InjectMocks private GmailMailboxSvc service;

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
				when(list.setQ("is:unread")).thenReturn(list);
			}
			when(list.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("same-id"))));
			when(gmail.users().messages().get("me", "same-id").setFormat("full").execute())
					.thenReturn(new Message().setId("same-id").setPayload(new MessagePart()));
		}

		// when
		final List<EmailMessage> emails = latest ? service.readLatestEmails(50) : service.readUnreadEmails();

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
				.setLabels(List.of(new Label().setName("Dev Jobs").setId("second-label"))));

		// when
		service.applyLabel("second@example.com", "same-id", "Dev Jobs");

		// then
		verify(secondGmail.users().messages()).modify(eq("me"), eq("same-id"),
				argThat(request -> request.getAddLabelIds().equals(List.of("second-label"))));
		verify(secondGmail.users().messages().modify(eq("me"), eq("same-id"), any())).execute();
		verifyNoInteractions(firstGmail);
	}
}
