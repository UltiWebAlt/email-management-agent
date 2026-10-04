package com.ultiweb.jobs.utils.email;

// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


// [START gmail_send_message]

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;

import java.io.IOException;
import java.util.List;
import javax.mail.MessagingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Class to demonstrate the use of Gmail Send Message API */
public final class SendMessage extends AbstractGmailMessageOperation {
	private static final Logger LOGGER = LoggerFactory.getLogger(SendMessage.class);

	private SendMessage() {
		throw new UnsupportedOperationException("Utility class");
	}

/**
 * Send an email from the user's mailbox to its recipient.
 *
 * @param gmailOAuth       - OAuth2 helper used to authorize Gmail access.
 * @param fromEmailAddress - Email address to appear in the from: header.
 * @param toEmailAddress   - Email address of the recipient.
 * @return the sent message.
 * @throws MessagingException - if a wrongly formatted address is encountered.
 * @throws IOException        - if authorization, credentials, or the Gmail API operation fails.
 */
public static Message sendEmail(GMailOAuth gmailOAuth,
                                String fromEmailAddress,
                                String toEmailAddress)
		throws MessagingException, IOException {
	Gmail service = GmailServiceFactory.create(gmailOAuth, List.of(GmailScopes.GMAIL_SEND), fromEmailAddress);
	return sendEmail(service, fromEmailAddress, toEmailAddress);
}

static Message sendEmail(Gmail service,
                         String fromEmailAddress,
                         String toEmailAddress)
		throws MessagingException, IOException {
	return sendEmail(
			message -> service.users().messages().send("me", message).execute(),
			fromEmailAddress,
			toEmailAddress);
}

static Message sendEmail(GmailMessageSender messageSender,
                         String fromEmailAddress,
                         String toEmailAddress)
		throws MessagingException, IOException {
	Message message = createMessage(fromEmailAddress, toEmailAddress);
	return execute("Sent Gmail message", "send Gmail message", LOGGER,
			() -> messageSender.send(message), Message::getId);
}

@FunctionalInterface
interface GmailMessageSender {
	Message send(Message message) throws IOException;
}
}
// [END gmail_send_message]
