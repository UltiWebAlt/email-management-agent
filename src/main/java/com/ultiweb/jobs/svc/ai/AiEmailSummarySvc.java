package com.ultiweb.jobs.svc.ai;

import com.ultiweb.jobs.svc.email.EmailMessage;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class AiEmailSummarySvc implements EmailSummarySvc {
	private static final String SUMMARY_INTRO = "Here is a summary of the email in three concise sentences:";
	private static final String DEFAULT_PROMPT_PATH = "prompts/email-summary-system-prompt.txt";

	private final EmailAiClient emailAiClient;
	private final Resource systemPromptResource;
	private volatile String systemPrompt;

	@Autowired
	public AiEmailSummarySvc(@Qualifier("summaryAiClient") final EmailAiClient emailAiClient) {
		this(emailAiClient, new ClassPathResource(DEFAULT_PROMPT_PATH));
	}

	AiEmailSummarySvc(final EmailAiClient emailAiClient, final Resource systemPromptResource) {
		this.emailAiClient = emailAiClient;
		this.systemPromptResource = systemPromptResource;
	}

	@PostConstruct
	void initializePrompt() {
		systemPrompt();
	}

	@Override
	public String summarize(final EmailMessage email) {
		final String response = emailAiClient.complete(systemPrompt(), emailPrompt(email));
		if (response == null || response.isBlank()) {
			throw new IllegalStateException("Email summarization returned an empty response");
		}
		return removeSummaryIntro(response);
	}

	private String systemPrompt() {
		String prompt = systemPrompt;
		if (prompt != null) {
			return prompt;
		}
		synchronized (this) {
			prompt = systemPrompt;
			if (prompt == null) {
				try {
					prompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
				} catch (final IOException exception) {
					throw new IllegalStateException("Unable to load email summary prompt from "
							+ systemPromptResource.getDescription(), exception);
				}
				systemPrompt = prompt;
			}
			return prompt;
		}
	}

	private static String removeSummaryIntro(final String response) {
		final String summary = response.strip();
		if (summary.regionMatches(true, 0, SUMMARY_INTRO, 0, SUMMARY_INTRO.length())) {
			return summary.substring(SUMMARY_INTRO.length()).stripLeading();
		}
		return summary;
	}

	static String emailPrompt(final EmailMessage email) {
		return "Subject: " + promptHeader(email.subject()) + "\n"
				+ "From: " + promptHeader(email.from()) + "\n\n"
				+ "Body (normalized visible text; links appear in parentheses and table cells use |):\n"
				+ email.body();
	}

	private static String promptHeader(final String value) {
		if (value == null) {
			return "";
		}
		final String normalized = value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
		return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
	}
}
