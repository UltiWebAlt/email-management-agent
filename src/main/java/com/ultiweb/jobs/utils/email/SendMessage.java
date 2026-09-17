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

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.codec.binary.Base64;

/* Class to demonstrate the use of Gmail Send Message API */
public class SendMessage {
/**
 * Send an email from the user's mailbox to its recipient.
 *
 * @param gmailOAuth       - OAuth2 helper used to authorize Gmail access.
 * @param fromEmailAddress - Email address to appear in the from: header.
 * @param toEmailAddress   - Email address of the recipient.
 * @return the sent message, {@code null} otherwise.
 * @throws MessagingException - if a wrongly formatted address is encountered.
 * @throws IOException        - if service account credentials file not found.
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
	// Create the email content
	String messageSubject = "Test message";
	String bodyText = "lorem ipsum.";

	// Encode as MIME message
	Properties props = new Properties();
	Session session = Session.getDefaultInstance(props, null);
	MimeMessage email = new MimeMessage(session);
	email.setFrom(new InternetAddress(fromEmailAddress));
	email.addRecipient(javax.mail.Message.RecipientType.TO,
			new InternetAddress(toEmailAddress));
	email.setSubject(messageSubject);
	email.setText(bodyText);

	// Encode and wrap the MIME message into a gmail message
	ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	email.writeTo(buffer);
	byte[] rawMessageBytes = buffer.toByteArray();
	String encodedEmail = Base64.encodeBase64URLSafeString(rawMessageBytes);
	Message message = new Message();
	message.setRaw(encodedEmail);

	try {
		// Create send message
		message = messageSender.send(message);
		System.out.println("Message id: " + message.getId());
		System.out.println(message.toPrettyString());
		return message;
	} catch (GoogleJsonResponseException e) {
		// TODO(developer) - handle error appropriately
		GoogleJsonError error = e.getDetails();
		if (error.getCode() == 403) {
			System.err.println("Unable to send message: " + e.getDetails());
		} else {
			throw e;
		}
	}
	return null;
}

@FunctionalInterface
interface GmailMessageSender {
	Message send(Message message) throws IOException;
}
}
// [END gmail_send_message]
