package com.ultiweb.jobs.svc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.email.EmailMessage;
import com.ultiweb.jobs.svc.email.GmailMailboxSvc;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.profiles.active=test,deepinfra",
				"gmail.polling.enabled=true",
				"gmail.polling.interval=50ms",
				"GMAIL_CLIENT_ID=test-client",
				"GMAIL_CLIENT_SECRET=test-secret",
				"DEEPINFRA_API_KEY=test-key"
		})
@DirtiesContext
class EmailPollingIntegrationTest {
	@MockitoBean private GmailMailboxSvc mailbox;
	@MockitoBean(name = "summaryAiClient") private EmailAiClient summaryClient;
	@MockitoBean(name = "tagAiClient") private EmailAiClient tagClient;

	@Test
	@Timeout(10)
	void runningApplicationChecksEmailsQueriesAiAndAppliesLabelsWithoutRepeatingInference() throws Exception {
		// given
		final EmailMessage email = new EmailMessage("first@example.com", "id", "Developer job", "jobs@example.com", "A job opening.");
		when(summaryClient.complete(startsWith("Summarize"), anyString())).thenReturn("Developer job opportunity.");
		when(tagClient.complete(startsWith("Classify"), anyString())).thenReturn("Dev_Jobs");
		when(mailbox.readArchitectEmailsPendingPersistence()).thenReturn(List.of());
		when(mailbox.readEmailsForTriage()).thenReturn(List.of(email));

		// when / then: the real application scheduler invokes the complete triage workflow
		verify(mailbox, timeout(5000)).applyLabel("first@example.com", "id", "Dev_Jobs");
		verify(mailbox, timeout(5000).atLeast(3)).readEmailsForTriage();
		verify(summaryClient, after(250).times(1)).complete(anyString(), anyString());
		verify(tagClient, times(1)).complete(anyString(), anyString());
		verify(mailbox, times(1)).applyLabel("first@example.com", "id", "Dev_Jobs");
	}
}
