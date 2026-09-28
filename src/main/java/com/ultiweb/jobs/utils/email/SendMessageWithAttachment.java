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


// [START gmail_send_message_with_attachment]

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;

import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.mail.MessagingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Class to demonstrate the use of Gmail Send Message with attachment API */
public final class SendMessageWithAttachment extends AbstractGmailMessageOperation {
	private static final Logger LOGGER = LoggerFactory.getLogger(SendMessageWithAttachment.class);

	private SendMessageWithAttachment() {
		throw new UnsupportedOperationException("Utility class");
	}

/**
 * Send an email with attachment from the user's mailbox to its recipient.
 *
 * @param gmailOAuth       - OAuth2 helper used to authorize Gmail access.
 * @param fromEmailAddress - Email address to appear in the from: header.
 * @param toEmailAddress   - Email address of the recipient.
 * @param file             - Path to the file to be attached.
 * @return the sent message, {@code null} otherwise.
 * @throws MessagingException - if a wrongly formatted address is encountered.
 * @throws IOException        - if service account credentials file not found.
 */
public static Message sendEmailWithAttachment(GMailOAuth gmailOAuth,
                                              String fromEmailAddress,
                                              String toEmailAddress,
                                              File file)
		throws MessagingException, IOException {
	Gmail service = GmailServiceFactory.create(gmailOAuth, List.of(GmailScopes.GMAIL_SEND), fromEmailAddress);
	Message message = createMessageWithAttachment(fromEmailAddress, toEmailAddress, file);
	return execute("Sent Gmail message with attachment", "send Gmail message with attachment", LOGGER,
			() -> service.users().messages().send("me", message).execute(), Message::getId);
}
}
// [END gmail_send_message_with_attachment]
