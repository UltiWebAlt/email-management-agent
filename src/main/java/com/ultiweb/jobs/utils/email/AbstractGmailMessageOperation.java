package com.ultiweb.jobs.utils.email;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.GenericJson;
import com.google.api.services.gmail.model.Message;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;

abstract class AbstractGmailMessageOperation {
	private static final String MESSAGE_SUBJECT = "Test message";
	private static final String BODY_TEXT = "lorem ipsum.";

	protected static Message createMessage(final String fromEmailAddress, final String toEmailAddress)
			throws MessagingException, IOException {
		final MimeMessage email = CreateEmail.createEmail(
				toEmailAddress, fromEmailAddress, MESSAGE_SUBJECT, BODY_TEXT);
		return CreateMessage.createMessageWithEmail(email);
	}

	protected static Message createMessageWithAttachment(final String fromEmailAddress,
			final String toEmailAddress, final File file) throws MessagingException, IOException {
		final MimeMessage email = CreateEmail.createEmailWithAttachment(
				toEmailAddress, fromEmailAddress, MESSAGE_SUBJECT, BODY_TEXT, file);
		return CreateMessage.createMessageWithEmail(email);
	}

	protected static <T extends GenericJson> T execute(final String successDescription,
			final String operationDescription, final Logger logger, final GmailApiCall<T> operation,
			final Function<T, String> idExtractor) throws IOException {
		try {
			final T result = operation.execute();
			if (logger.isInfoEnabled()) {
				logger.info("{}: id={}", successDescription, idExtractor.apply(result));
			}
			if (logger.isDebugEnabled()) {
				logger.debug("{} response: {}", successDescription, result.toPrettyString());
			}
			return result;
		} catch (GoogleJsonResponseException exception) {
			final GoogleJsonError error = exception.getDetails();
			if (error != null && error.getCode() == 403) {
				logger.error("Unable to {}: {}", operationDescription, error, exception);
			}
			throw exception;
		}
	}

	@FunctionalInterface
	protected interface GmailApiCall<T extends GenericJson> {
		T execute() throws IOException;
	}
}
