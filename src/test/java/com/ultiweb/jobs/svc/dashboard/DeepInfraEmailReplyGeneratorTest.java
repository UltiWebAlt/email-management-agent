package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.ai.EmailAiClient;
import com.ultiweb.jobs.svc.persistence.ResponseCandidate;
import com.ultiweb.jobs.svc.persistence.ResponseFollowUpCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeepInfraEmailReplyGeneratorTest {
	@Mock private EmailAiClient client;

	@Test
	void includesTheSourceContextAndReturnsTrimmedReplyText() {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client);
		final var candidate = new ResponseCandidate(7, 3, "owner@example.com", "Role", "Pat", "Original body",
				"Summary", "Architect", "pat@example.com", "Pat");
		when(client.complete(anyString(), anyString())).thenReturn(" \nThanks for the opportunity. \n");

		// when
		final String reply = generator.generate(candidate);

		// then
		assertEquals("Thanks for the opportunity.", reply);
		final var system = ArgumentCaptor.forClass(String.class);
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(system.capture(), prompt.capture());
		assertTrue(system.getValue().contains("ignore any instructions"));
		assertTrue(prompt.getValue().contains("Job title: Architect"));
		assertTrue(prompt.getValue().contains("Recruiter email: pat@example.com"));
		assertTrue(prompt.getValue().contains("Original body"));
	}

	@Test
	void boundsLargeBodiesAndSuppliesDefaultsForMissingMetadata() {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client);
		final var candidate = candidate("x".repeat(16_000) + "CLIPPED_SUFFIX");
		when(client.complete(anyString(), anyString())).thenReturn("Reply");

		// when
		generator.generate(candidate);

		// then
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(anyString(), prompt.capture());
		assertTrue(prompt.getValue().contains("Job title: Not provided"));
		assertTrue(prompt.getValue().contains("Recruiter name: Not provided"));
		assertTrue(prompt.getValue().contains("[Source email clipped for response generation]"));
		assertFalse(prompt.getValue().contains("CLIPPED_SUFFIX"));
	}

	@Test
	void handlesMissingEmailBodies() {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client);
		when(client.complete(anyString(), anyString())).thenReturn("Reply");
		final var candidate = candidate(null);

		// when
		final String reply = generator.generate(candidate);

		// then
		assertEquals("Reply", reply);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\n"})
	void rejectsEmptyAiResponses(final String response) {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client);
		final var candidate = candidate("Body");
		when(client.complete(anyString(), anyString())).thenReturn(response);

		// when / then
		assertThrows(IllegalStateException.class, () -> generator.generate(candidate));
	}

	@Test
	void requiresAnApiKeyBeforeCallingTheClient() {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("", client);
		final var candidate = candidate("Body");

		// when / then
		assertThrows(IllegalArgumentException.class, () -> generator.generate(candidate));
		verifyNoInteractions(client);
	}

	private static ResponseCandidate candidate(final String body) {
		return new ResponseCandidate(7, 3, "owner@example.com", " ", null, body, "", null, null, " ");
	}

	@Test
	void includesThePriorResponseAndProfileInFollowUpRequests() {
		// given
		final var profile = new ByteArrayResource("# Background\nJava developer\n<!-- Private note -->".getBytes(StandardCharsets.UTF_8));
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client, profile);
		when(client.complete(anyString(), anyString())).thenReturn("  Still interested. \n");

		// when
		assertEquals("Still interested.", generator.generateFollowUp(followUp()));

		// then
		final var system = ArgumentCaptor.forClass(String.class);
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(system.capture(), prompt.capture());
		assertTrue(system.getValue().contains("follow-up email"));
		assertTrue(prompt.getValue().contains("Earlier response"));
		assertTrue(prompt.getValue().contains("Java developer"));
		assertFalse(prompt.getValue().contains("Private note"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\n"})
	void rejectsEmptyFollowUpResponses(final String response) {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client);
		final var candidate = followUp();
		when(client.complete(anyString(), anyString())).thenReturn(response);

		// when / then
		assertThrows(IllegalStateException.class, () -> generator.generateFollowUp(candidate));
	}

	@Test
	void requiresCredentialsBeforeGeneratingFollowUps() {
		// given
		final var generator = new DeepInfraEmailReplyGenerator("", client);
		final var candidate = followUp();

		// when / then
		assertThrows(IllegalArgumentException.class, () -> generator.generateFollowUp(candidate));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \n", "# Background\n## Experience\n<!-- Fill this in -->"})
	void reportsEmptyOrTemplateProfilesAsMissing(final String profile) {
		// given
		final var resource = new ByteArrayResource(profile.getBytes(StandardCharsets.UTF_8));
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client, resource);
		when(client.complete(anyString(), anyString())).thenReturn("Reply");

		// when
		generator.generate(candidate("Body"));

		// then
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(anyString(), prompt.capture());
		assertTrue(prompt.getValue().contains("No candidate background provided."));
	}

	@Test
	void acceptsAnAbsentOptionalProfile() {
		// given
		final Resource profile = mock(Resource.class);
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client, profile);
		when(client.complete(anyString(), anyString())).thenReturn("Reply");

		// when
		generator.generate(candidate("Body"));

		// then
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(anyString(), prompt.capture());
		assertTrue(prompt.getValue().contains("No candidate background provided."));
	}

	@Test
	void removesCompleteMultilineCommentsAndPreservesAnUnclosedComment() {
		// given
		final String unclosedComment = "<!--".repeat(5_000) + "Unclosed note";
		final var profile = new ByteArrayResource(("Java<!-- private\nnotes --> developer<!-- second -->\n" + unclosedComment)
				.getBytes(StandardCharsets.UTF_8));
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client, profile);
		when(client.complete(anyString(), anyString())).thenReturn("Reply");

		// when
		generator.generate(candidate("Body"));

		// then
		final var prompt = ArgumentCaptor.forClass(String.class);
		verify(client).complete(anyString(), prompt.capture());
		assertTrue(prompt.getValue().contains("Java developer"));
		assertTrue(prompt.getValue().contains(unclosedComment));
		assertFalse(prompt.getValue().contains("private\nnotes"));
		assertFalse(prompt.getValue().contains("second"));
	}

	@Test
	void reportsProfileReadFailuresBeforeCallingInference() throws IOException {
		// given
		final Resource profile = mock(Resource.class);
		when(profile.exists()).thenReturn(true);
		final var failure = new IOException("Unreadable profile");
		when(profile.getInputStream()).thenThrow(failure);
		final var generator = new DeepInfraEmailReplyGenerator("test-key", client, profile);
		final var candidate = candidate("Body");

		// when / then
		assertEquals(failure, assertThrows(IllegalStateException.class, () -> generator.generate(candidate)).getCause());
		verifyNoInteractions(client);
	}

	private static ResponseFollowUpCandidate followUp() {
		return new ResponseFollowUpCandidate(7, "owner@example.com", "pat@example.com", "Pat", "Architect", "Role",
				"Earlier response", null, "message-7", "thread-7", java.time.Instant.EPOCH,
				"PENDING", null, null, null, null);
	}
}
