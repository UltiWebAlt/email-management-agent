package com.ultiweb.jobs.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provides a development-only version token so open dashboard tabs can reload changed static assets. */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
public final class DevelopmentReloadController {
	private static final List<String> WATCHED_ASSETS = List.of(
			"static/index.html", "static/app.js", "static/app.css");
	private final ResourceLoader resourceLoader;

	public DevelopmentReloadController(final ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@GetMapping(path = "/static-version", produces = "text/plain")
	public ResponseEntity<String> staticVersion() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(version());
	}

	private String version() {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (final String asset : WATCHED_ASSETS) {
				digest.update(asset.getBytes(StandardCharsets.UTF_8));
				try (final var input = resourceLoader.getResource("classpath:" + asset).getInputStream();
						final var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
					input.transferTo(output);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (final IOException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Unable to check dashboard asset version", exception);
		}
	}
}
