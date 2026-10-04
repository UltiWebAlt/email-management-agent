package com.ultiweb.jobs.utils.email;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

class AbstractGmailMessageOperationTest {
	@Test
	void propagatesForbiddenResponsesInsteadOfReturningANullMessage() {
		// given
		final var details = new GoogleJsonError();
		details.setCode(403);
		final var failure = new GoogleJsonResponseException(
				new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders()), details);
		final SendMessage.GmailMessageSender sender = message -> { throw failure; };

		// when
		final GoogleJsonResponseException thrown = assertThrows(GoogleJsonResponseException.class,
				() -> SendMessage.sendEmail(sender, "sender@example.com", "recipient@example.com"));

		// then
		assertSame(failure, thrown);
	}
}
