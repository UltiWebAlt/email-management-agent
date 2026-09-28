package com.ultiweb.jobs.svc.dashboard;

import com.ultiweb.jobs.svc.persistence.ArchitectOpportunityAddedEvent;
import com.ultiweb.jobs.svc.persistence.ArchitectOpportunityUpdatedEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Streams lightweight dashboard invalidation events to connected browsers. */
@Component
public final class DashboardEventStream {
	private static final Logger LOGGER = LoggerFactory.getLogger(DashboardEventStream.class);
	private static final long CONNECTION_TIMEOUT_MILLIS = 30 * 60 * 1000L;
	private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public SseEmitter connect() {
		final SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(error -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().name("connected").data(Map.of("connected", true)));
		} catch (final IOException exception) {
			emitters.remove(emitter);
			emitter.completeWithError(exception);
		}
		return emitter;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void opportunityAdded(final ArchitectOpportunityAddedEvent event) {
		emit("opportunity-added");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void opportunityUpdated(final ArchitectOpportunityUpdatedEvent event) {
		emit("opportunity-updated");
	}

	@EventListener
	public void responseUpdated(final DashboardResponseUpdatedEvent event) {
		emit("response-updated");
	}

	private void emit(final String eventName) {
		for (final SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name(eventName).data(Map.of("refresh", true)));
			} catch (final IOException | IllegalStateException exception) {
				emitters.remove(emitter);
				LOGGER.debug("Removing disconnected dashboard event stream ({}).",
					exception.getClass().getSimpleName());
			}
		}
	}
}
