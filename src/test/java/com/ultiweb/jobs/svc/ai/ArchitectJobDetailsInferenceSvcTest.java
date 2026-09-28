package com.ultiweb.jobs.svc.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.email.EmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchitectJobDetailsInferenceSvcTest {
	@Mock private EmailAiClient emailAiClient;

	@Test
	void extractsOnlyStructuredValuesReturnedForTheRole() {
		// given
		final var service = new ArchitectJobDetailsInferenceSvc(emailAiClient);
		final var email = new EmailMessage("owner", "id", "Architect role", "Recruiter", "Role details");
		when(emailAiClient.complete(anyString(), anyString())).thenReturn("""
				{"title":"Senior Architect","company":"Example Co","location":"Boston, MA",
				"remote":false,"salaryRange":"$150,000-$180,000","requirements":"Design leadership"}
				""");

		// when
		final var details = service.infer(email);

		// then
		assertEquals("Senior Architect", details.title());
		assertEquals("Example Co", details.company());
		assertEquals("Boston, MA", details.location());
		assertEquals(false, details.remote());
		assertEquals("$150,000-$180,000", details.salaryRange());
		assertEquals("Design leadership", details.requirements());
	}
}
