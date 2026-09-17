package com.ultiweb.jobs.svc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.profiles.active=deepinfra",
				"gmail.polling.enabled=true",
				"gmail.polling.interval=50ms",
				"GMAIL_CLIENT_ID=test-client",
				"GMAIL_CLIENT_SECRET=test-secret",
				"DEEPINFRA_API_KEY=test-key"
		})
@DirtiesContext
class EmailPollingIntegrationTest {
	@MockitoBean private GmailMailboxSvc mailbox;
	@MockitoBean private EmailAiClient aiClient;

	@Test
	@Timeout(10)
	void runningApplicationChecksEmailsQueriesAiAndAppliesLabelsWithoutRepeatingInference() throws Exception {
		// given
		final EmailMessage email = new EmailMessage("first@example.com", "id", "Developer job", "jobs@example.com", "A job opening.");
		when(aiClient.complete(startsWith("Summarize"), anyString())).thenReturn("Developer job opportunity.");
		when(aiClient.complete(startsWith("Assign exactly"), anyString())).thenReturn("Dev Jobs");
		when(mailbox.readUnreadEmails()).thenReturn(List.of(email));

		// when / then: the real application scheduler invokes the complete triage workflow
		verify(mailbox, timeout(5000)).applyLabel("first@example.com", "id", "Dev Jobs");
		verify(mailbox, timeout(5000).atLeast(3)).readUnreadEmails();
		verify(aiClient, after(250).times(2)).complete(anyString(), anyString());
		verify(mailbox, times(1)).applyLabel("first@example.com", "id", "Dev Jobs");
	}
}
