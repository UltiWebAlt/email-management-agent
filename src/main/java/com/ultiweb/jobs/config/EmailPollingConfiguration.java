package com.ultiweb.jobs.config;

import com.ultiweb.jobs.svc.EmailTriageSvc;
import com.ultiweb.jobs.svc.EmailPollingJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "gmail.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmailPollingConfiguration {
	@Bean
	EmailPollingJob emailPollingJob(final EmailTriageSvc triageService) {
		return new EmailPollingJob(triageService);
	}
}
