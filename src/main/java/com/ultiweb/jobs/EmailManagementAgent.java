package com.ultiweb.jobs;

// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// [START email_management_agent]

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import com.ultiweb.jobs.utils.oauth2.GmailOAuthProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/* class to demonstrate use of Gmail list labels API */
@SpringBootApplication
@EnableConfigurationProperties(GmailOAuthProperties.class)
public class EmailManagementAgent {
private static final Logger logger = LoggerFactory.getLogger(EmailManagementAgent.class);

/**
 * Application name.
 */
private static final String APPLICATION_NAME = "Email Management Agent";
/**
 * Global instance of the JSON factory.
 */
private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
private final GMailOAuth gmailOAuth;

EmailManagementAgent(GMailOAuth gmailOAuth) {
	this.gmailOAuth = gmailOAuth;
}

public static void main(String... args) {
	System.exit(SpringApplication.exit(SpringApplication.run(EmailManagementAgent.class, args)));
}

@Bean
CommandLineRunner listLabels() {
	return args -> {
		// Build a new authorized API client service.
		final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport.Builder().build();
		Gmail service = new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, gmailOAuth.authorize(HTTP_TRANSPORT))
				.setApplicationName(APPLICATION_NAME)
				.build();

		// Print the labels in the user's account.
		String user = "me";
		ListLabelsResponse listResponse = service.users().labels().list(user).execute();
		List<Label> labels = listResponse.getLabels();
		if (labels.isEmpty()) {
			logger.info("No labels found.");
		} else {
			logger.info("Labels:");
			for (Label label : labels) {
				logger.info("- {}", label.getName());
			}
		}
	};
}
}
// [END email_management_agent]
