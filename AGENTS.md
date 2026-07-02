# AGENTS.md - Java/Spring Boot Development Guide

## Project Context
	- Tech Stack: Java 21, Spring Boot 4.1.x, Gradle (Kotlin or Groovy DSL), JUnit 5, Mockito.
	- Core Rules: Maintain strict layered architecture: Controller -> Service -> Repository.
	- Code Style: Strict indentation using TABS instead of spaces. Never mix tabs and spaces.

## Project Overview

    - Main application entry point: `src/main/java/com/ultiweb/jobs/EmailManagementAgent.java`.
    - Gmail OAuth2 support lives in `src/main/java/com/ultiweb/jobs/utils/oauth2`.
    - Gmail email examples live in `src/main/java/com/ultiweb/jobs/utils/email`.
    - Unit tests live under `src/test/java`.
    - Integration tests live under `src/integrationTest/java`.

## Java & Spring Boot Standards
	- Language Version: Always utilize Java 21 syntax features (e.g., Records, Pattern Matching, Switch Expressions).
	- Dependency Injection: Always use constructor injection. Do NOT use `@Autowired` on fields.
	- Immutability: Prefer Java `record` classes over standard classes for DTOs and Data Carriers.
	- Formatting Contract: Ensure your editor configuration applies heavy tabs for formatting all `.java` files.
    - Prefer the existing `GmailServiceFactory` and `GMailOAuth` helpers for Gmail clients.
    - Avoid static mocking when a small package-private test seam is enough.
    - Keep unit tests isolated from network, browser, OAuth, and filesystem token state.
    - Put network or real Gmail behavior under `src/integrationTest/java` and gate it explicitly.
    - Preserve Java package structure under `com.ultiweb.jobs`.

## Code Example: Mapper & DTO Pattern
	- Always separate database entities from REST communication payloads. Use static mappers:
```java
public final class UserMapper {
	private UserMapper() {
		throw new UnsupportedOperationException("Utility class");
	}

	public static UserDTO toDto(final User user) {
		if (user == null) {
			return null;
		}
		return new UserDTO(user.getId(), user.getEmail());
	}
}
```
## Exception Handling & Responses
	- Custom Exceptions: Extend `RuntimeException` for explicit business exceptions.
	- Global Handlers: Use `@RestControllerAdvice` along with `@ExceptionHandler` mechanisms.
	- Structure: Always return a standardized JSON error envelope containing a `timestamp`, `status`, and `message`.

## Testing Best Practices
	- Structure: Write clean `given / when / then` blocks inside all unit tests.
	- Isolation: Use Mockito `@Mock` and `@InjectMocks` for unit slice testing.
	- Web Layer: Use `@WebMvcTest(YourController.class)` to test controllers efficiently without full application contexts.

## Build And Test Commands
Use the Gradle wrapper from this directory:

```bash
./gradlew compileJava
./gradlew test
./gradlew integrationTest
./gradlew check
```

Run the focused `SendMessage` tests with:

```bash
./gradlew test --tests com.ultiweb.jobs.utils.email.SendMessageTest
./gradlew integrationTest --tests com.ultiweb.jobs.utils.email.SendMessageIntegrationTest
```

## OAuth And Credentials

      - OAuth client configuration is read from `src/main/resources/application.yml`.
      - `GMAIL_CLIENT_ID` and `GMAIL_CLIENT_SECRET` must come from the environment.
      - OAuth tokens are stored in the local `tokens/` directory.
      - Do not commit credentials, generated tokens, or local OAuth artifacts.
      - If adding new Gmail scopes, use the existing `GMailOAuth` helper rather than creating a separate credentials flow.

## Gmail Send Integration Tests

The real Gmail send test is intentionally opt-in. Do not make real Gmail API calls during normal test runs.

To run the real send test:

```bash
RUN_GMAIL_SEND_INTEGRATION_TEST=true ./gradlew integrationTest --tests com.ultiweb.jobs.utils.email.SendMessageIntegrationTest.sendEmailSendsRealMessageFromRandallToRandall
```

Normal `integrationTest` and `check` runs should use mock transport tests and must not send email.

## Dependency Guidelines

      - Keep Google API client dependencies current and avoid adding duplicate older Gmail service artifacts.
      - Prefer versions already managed by the Spring Boot dependency platform unless there is a specific reason to pin.
      - After dependency changes, run:
```bash
./gradlew dependencyInsight --configuration runtimeClasspath --dependency google-api-client
./gradlew check
```

## Git Standards

- All commits must be signed with GnuPG.
- Do not revert unrelated user changes.
- Keep edits scoped to this sample unless explicitly asked to modify the wider repository.
- Before summarizing work, run `git status --short` and mention any untracked or unrelated files only when relevant.
