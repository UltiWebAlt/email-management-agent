package com.ultiweb.jobs.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HiringCompanyExtractorTest {
	@ParameterizedTest
	@ValueSource(strings = {"GE Vernova is hiring a Lead Architect", "GE Vernova... is hiring an Architect"})
	void extractsTheExplicitCompanyAndRemovesTrailingPunctuation(final String text) {
		// given / when
		final String company = HiringCompanyExtractor.extract(text);

		// then
		assertEquals("GE Vernova", company);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "Architect opportunity", "Our company is hiring an Architect", "The team is hiring"})
	void leavesCompanyUnknownWhenNoSpecificCompanyIsNamed(final String text) {
		// given / when
		final String company = HiringCompanyExtractor.extract(text);

		// then
		assertNull(company);
	}
}
