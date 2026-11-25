package com.ultiweb.jobs.svc.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultiweb.jobs.svc.email.EmailWorkflowLabels;
import com.ultiweb.jobs.svc.email.FollowUpEmail;
import com.ultiweb.jobs.svc.email.GmailDraftSvc;
import com.ultiweb.jobs.svc.email.GmailIncomingReply;
import com.ultiweb.jobs.svc.email.GmailSentMessage;
import com.ultiweb.jobs.svc.persistence.JobResponseRepository;
import com.ultiweb.jobs.svc.persistence.ResponseFollowUpCandidate;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ResponseFollowUpSvcTest {
	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	private static final Instant SENT_AT = NOW.minusSeconds(3 * 24 * 60 * 60L);

	@Mock private JobResponseRepository repository;
	@Mock private GmailDraftSvc gmailDraftSvc;
	@Mock private EmailReplyGenerator replyGenerator;
	@Mock private ApplicationEventPublisher eventPublisher;

	@Test
	void claimsDueResponsesAndSendsStoredFollowUpInProduction() throws Exception {
		// given
		final var candidate = candidate("Existing follow-up", null);
		final var service = service(true);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT)).thenReturn(Optional.empty());
		when(gmailDraftSvc.sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Existing follow-up"), null))
				.thenReturn(new GmailSentMessage(null, "sent-7", "thread-7", NOW, true));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).releaseStaleFollowUpChecks(NOW.minusSeconds(2 * 60 * 60L), NOW);
		verify(gmailDraftSvc).sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Existing follow-up"), null);
		verify(repository).markFollowUpSent(7, "Existing follow-up", "sent-7", NOW);
		verify(eventPublisher).publishEvent(new DashboardResponseUpdatedEvent(7));
		verify(repository, never()).storeFollowUpContent(any(Long.class), any(String.class), any(Instant.class));
		verifyNoInteractions(replyGenerator);
	}

	@Test
	void restoresPreviouslySentFollowUpAndNotifiesWithoutSendingAgain() throws Exception {
		// given
		final var candidate = candidate(null, null);
		final var existing = new GmailSentMessage(null, "sent-7", "thread-7", NOW.minusSeconds(10), true);
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.of(existing));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).markFollowUpSent(7, null, "sent-7", NOW.minusSeconds(10));
		verify(eventPublisher).publishEvent(new DashboardResponseUpdatedEvent(7));
		verify(gmailDraftSvc, never()).findExternalReply("owner@example.com", "thread-7", SENT_AT);
		verify(gmailDraftSvc, never()).sendFollowUp(any(), any(FollowUpEmail.class), any());
	}

	@Test
	void marksAnIncomingReplyAndNotifiesWithoutSendingFollowUp() throws Exception {
		// given
		final var candidate = candidate(null, null);
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT))
				.thenReturn(Optional.of(new GmailIncomingReply("reply-7", NOW.minusSeconds(100))));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).markReplied(7, "reply-7", NOW.minusSeconds(100));
		verify(eventPublisher).publishEvent(new DashboardResponseUpdatedEvent(7));
		verify(gmailDraftSvc, never()).sendFollowUp(any(), any(FollowUpEmail.class), any());
	}

	@Test
	void marksAnAlreadySentButUnansweredFollowUpWithoutResending() throws Exception {
		// given
		final var candidate = candidate("Stored content", SENT_AT.plusSeconds(60));
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT)).thenReturn(Optional.empty());

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).markFollowUpUnanswered(7, NOW);
		verify(eventPublisher, never()).publishEvent(any());
		verify(gmailDraftSvc, never()).sendFollowUp(any(), any(FollowUpEmail.class), any());
	}

	@Test
	void generatesMissingContentAndUsesTestMailboxAndLabelOutsideProduction() throws Exception {
		// given
		final var candidate = candidate(" \n ", null);
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT)).thenReturn(Optional.empty());
		when(replyGenerator.generateFollowUp(candidate)).thenReturn("Generated response");
		when(gmailDraftSvc.sendFollowUp("owner@example.com", followUp("owner@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Generated response"), EmailWorkflowLabels.EMAIL_AGENT_TEST))
				.thenReturn(new GmailSentMessage(null, "sent-7", "thread-7", null, true));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).storeFollowUpContent(7, "Generated response", NOW);
		verify(gmailDraftSvc).sendFollowUp("owner@example.com", followUp("owner@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Generated response"), EmailWorkflowLabels.EMAIL_AGENT_TEST);
		verify(repository).markFollowUpSent(7, "Generated response", "sent-7", NOW);
		verify(eventPublisher).publishEvent(new DashboardResponseUpdatedEvent(7));
	}

	@Test
	void normalizesReplySubjectAndFallsBackWhenTheSourceSubjectIsMissing() throws Exception {
		// given
		final var first = candidate("Stored", null);
		final var missingSubject = new ResponseFollowUpCandidate(8, "owner@example.com", "recruiter@example.com",
				"Pat", "Architect", null, "Response", "Stored", "message-8", "thread-8", SENT_AT,
				"DUE", null, null, null, null);
		final var service = service(true);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(first, missingSubject));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(first));
		when(repository.claimFollowUp(8, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(missingSubject));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-8", 8)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-8", SENT_AT)).thenReturn(Optional.empty());
		when(gmailDraftSvc.sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Stored"), null))
				.thenReturn(new GmailSentMessage(null, "sent-7", "thread-7", NOW, true));
		when(gmailDraftSvc.sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-8", 8,
				"message-8", "Re: Architect job opportunity", "Stored"), null))
				.thenReturn(new GmailSentMessage(null, "sent-8", "thread-8", NOW, true));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(gmailDraftSvc).sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Stored"), null);
		verify(gmailDraftSvc).sendFollowUp("owner@example.com", followUp("recruiter@example.com", "thread-8", 8,
				"message-8", "Re: Architect job opportunity", "Stored"), null);
		assertEquals(2, repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L)).size());
	}

	@Test
	void marksFollowUpFailedAndNotifiesWhenGmailThrows() throws Exception {
		// given
		final var candidate = candidate("Stored", null);
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(candidate));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.of(candidate));
		when(gmailDraftSvc.findExistingFollowUp("owner@example.com", "thread-7", 7)).thenReturn(Optional.empty());
		when(gmailDraftSvc.findExternalReply("owner@example.com", "thread-7", SENT_AT)).thenReturn(Optional.empty());
		when(gmailDraftSvc.sendFollowUp("owner@example.com", followUp("owner@example.com", "thread-7", 7,
				"message-7", "Re: Senior Architect role", "Stored"), EmailWorkflowLabels.EMAIL_AGENT_TEST))
				.thenThrow(new IOException("Gmail unavailable"));

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).markFollowUpFailed(7, NOW);
		verify(eventPublisher).publishEvent(new DashboardResponseUpdatedEvent(7));
	}

	@Test
	void continuesAfterAClaimFailureAndSkipsCandidatesAlreadyClaimedElsewhere() {
		// given
		final var first = candidate("Stored", null);
		final var second = new ResponseFollowUpCandidate(8, "owner@example.com", "recruiter@example.com",
				"Pat", "Architect", "Re: Another role", "Response", "Stored", "message-8", "thread-8", SENT_AT,
				"DUE", null, null, null, null);
		final var service = service(false);
		when(repository.findResponsesForFollowUp(NOW.minusSeconds(48 * 60 * 60L))).thenReturn(List.of(first, second));
		when(repository.claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenThrow(new IllegalStateException("database busy"));
		when(repository.claimFollowUp(8, NOW, NOW.minusSeconds(48 * 60 * 60L))).thenReturn(Optional.empty());

		// when
		service.checkForResponsesAndSendDueFollowUps();

		// then
		verify(repository).claimFollowUp(7, NOW, NOW.minusSeconds(48 * 60 * 60L));
		verify(repository).claimFollowUp(8, NOW, NOW.minusSeconds(48 * 60 * 60L));
		verifyNoInteractions(gmailDraftSvc, replyGenerator, eventPublisher);
	}

	private ResponseFollowUpSvc service(final boolean productionMode) {
		return new ResponseFollowUpSvc(repository, gmailDraftSvc, replyGenerator, eventPublisher,
				Clock.fixed(NOW, ZoneOffset.UTC), productionMode);
	}

	private static ResponseFollowUpCandidate candidate(final String followUpContent, final Instant followUpSentAt) {
		return new ResponseFollowUpCandidate(7, "owner@example.com", "recruiter@example.com", "Pat", "Senior Architect",
				"  Senior\n Architect role  ", "Recruiter response", followUpContent, "message-7", "thread-7", SENT_AT,
				followUpSentAt == null ? "DUE" : "SENT", followUpSentAt, null, null, null);
	}

	private static FollowUpEmail followUp(final String recipient, final String threadId, final long positionId,
			final String parentMessageId, final String subject, final String body) {
		return new FollowUpEmail(recipient, threadId, positionId, parentMessageId, subject, body);
	}
}
