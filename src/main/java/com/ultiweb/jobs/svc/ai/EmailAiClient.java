package com.ultiweb.jobs.svc.ai;

/**
 * Sends one prompt to the LiteLLM email-router model and returns the raw reply.
 *
 * <p>Implementations are composed by {@code EmailInferenceConfiguration}: a concurrency limit, an
 * optional {@link InferenceTraceEmailAiClient}, and {@link SpringAiEmailClient}. Callers should not
 * assume a nonblank response. A reply of {@code null}, an empty string, or whitespace means the
 * upstream returned nothing; validating and rejecting that is each caller's responsibility, which
 * is why {@code AiEmailTagSvc} and {@code EmailReplyGenerator} fail the email rather than storing
 * an unusable value. Transport and authentication failures surface as unchecked exceptions.
 */
@FunctionalInterface
public interface EmailAiClient {
	/**
	 * Completes one prompt through the shared router model.
	 *
	 * @param systemPrompt the fixed instruction prefix, or {@code null} when the implementation takes
	 * no system message
	 * @param userPrompt the variable, untrusted content such as an email body or its summary
	 * @return the raw model reply, which may be {@code null} or blank
	 */
	String complete(String systemPrompt, String userPrompt);
}
