package com.ultiweb.jobs.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/** Fails startup when the LiteLLM API key is missing instead of failing on the first email. */
@Configuration(proxyBeanMethods = false)
public class LiteLlmConfiguration {
	@Autowired
	public LiteLlmConfiguration(@Value("${spring.ai.openai.api-key:}") final String apiKey) {
		Assert.hasText(apiKey, "LITELLM_API_KEY must be set; LiteLLM email-router serves every inference request");
	}
}
