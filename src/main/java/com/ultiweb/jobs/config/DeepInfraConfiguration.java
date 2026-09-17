package com.ultiweb.jobs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("deepinfra")
public class DeepInfraConfiguration {
	public DeepInfraConfiguration(@Value("${DEEPINFRA_API_KEY:}") final String apiKey) {
		Assert.hasText(apiKey, "DEEPINFRA_API_KEY must be set when the deepinfra profile is active");
	}
}
