package com.ultiweb.jobs.svc.ai;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum EmailTag {
	DEV_JOBS("Dev_Jobs"),
	ARCHITECT_JOBS("Architect_Jobs"),
	MANAGEMENT_JOBS("Management_Jobs"),
	MISC_JOBS("Misc_Jobs"),
	TECH_NEWS_PUBLICATIONS("Tech_News_Publications"),
	GENERAL_NEWS_PUBLICATIONS("General_News_Publications"),
	PROMOTIONS_COMMERCIAL("Promotions_Commercial"),
	RECEIPTS("Receipts"),
	DELIVERY_NOTIFICATION("Delivery_Notification"),
	SECURITY_ALERT("Security_Alert"),
	PERSONAL("Personal"),
	SOCIAL_MEDIA("Social_Media");

	private static final Map<String, EmailTag> BY_LABEL = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(EmailTag::labelName, Function.identity()));

	private final String labelName;

	EmailTag(final String labelName) {
		this.labelName = labelName;
	}

	public String labelName() {
		return labelName;
	}

	public static Optional<EmailTag> fromLabel(final String label) {
		return Optional.ofNullable(BY_LABEL.get(label));
	}

	static String promptLabels() {
		return Arrays.stream(values()).map(EmailTag::labelName).collect(Collectors.joining(", "));
	}
}
