package com.ultiweb.jobs.utils.email;

// Copyright 2021 Google LLC
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


// [START gmail_create_draft]

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.Message;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;

import java.io.IOException;
import java.util.List;
import javax.mail.MessagingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Class to demonstrate the use of Gmail Create Draft API */
public final class CreateDraft extends AbstractGmailMessageOperation {
	private static final Logger LOGGER = LoggerFactory.getLogger(CreateDraft.class);

	private CreateDraft() {
		throw new UnsupportedOperationException("Utility class");
	}

/**
 * Create a draft email.
 *
 * @param gmailOAuth       - OAuth2 helper used to authorize Gmail access.
 * @param fromEmailAddress - Email address to appear in the from: header.
 * @param toEmailAddress   - Email address of the recipient.
 * @return the created draft.
 * @throws MessagingException - if a wrongly formatted address is encountered.
 * @throws IOException        - if authorization, credentials, or the Gmail API operation fails.
 */
public static Draft createDraftMessage(GMailOAuth gmailOAuth,
                                       String fromEmailAddress,
                                       String toEmailAddress)
		throws MessagingException, IOException {
	Gmail service = GmailServiceFactory.create(gmailOAuth, List.of(GmailScopes.GMAIL_COMPOSE), fromEmailAddress);

	Message message = createMessage(fromEmailAddress, toEmailAddress);
	Draft draft = new Draft().setMessage(message);
	return execute("Created Gmail draft", "create Gmail draft", LOGGER,
			() -> service.users().drafts().create("me", draft).execute(), Draft::getId);
}
}
// [END gmail_create_draft]
