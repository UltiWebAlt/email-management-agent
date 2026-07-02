package com.ultiweb.jobs.utils.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.api.services.gmail.model.Message;

import java.io.ByteArrayInputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.Test;

class SendMessageTest {

@Test
void sendEmailSendsEncodedMimeMessage() throws Exception {
	AtomicReference<Message> sentApiMessage = new AtomicReference<>();
	Message sentMessage = new Message().setId("message-123");

	Message result = SendMessage.sendEmail(
			message -> {
				sentApiMessage.set(message);
				return sentMessage;
			},
			"sender@example.com",
			"recipient@example.com");

	assertEquals(sentMessage, result);

	Message apiMessage = sentApiMessage.get();
	assertNotNull(apiMessage);
	assertNotNull(apiMessage.getRaw());

	byte[] decodedMessage = Base64.decodeBase64(apiMessage.getRaw());
	MimeMessage mimeMessage = new MimeMessage(
			Session.getDefaultInstance(new Properties(), null),
			new ByteArrayInputStream(decodedMessage));

	InternetAddress from = (InternetAddress) mimeMessage.getFrom()[0];
	InternetAddress to = (InternetAddress) mimeMessage.getRecipients(
			javax.mail.Message.RecipientType.TO)[0];

	assertEquals("sender@example.com", from.getAddress());
	assertEquals("recipient@example.com", to.getAddress());
	assertEquals("Test message", mimeMessage.getSubject());
	assertEquals("lorem ipsum.", mimeMessage.getContent().toString().trim());
}
}
