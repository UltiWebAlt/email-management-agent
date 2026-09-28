package com.ultiweb.jobs.svc.ai;

public interface EmailAiClient {
	String complete(String systemPrompt, String userPrompt);
}
