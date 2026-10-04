# Email Management Agent

SonarQube analysis uses a local SonarQube server at `http://localhost:9000`. With that server running and `SONAR_TOKEN` set in your environment, run `./gradlew sonar`. The task runs unit and integration tests and generates JaCoCo coverage before analysis. For a different self-hosted server address, use `./gradlew sonar -Dsonar.host.url=http://your-server:9000`. SonarQube analysis is opt-in and is not run by the GitHub workflows or the normal `check` task.

Configure the Gmail accounts in `src/main/resources/application.yml` using a comma-separated list:

```yaml
gmail:
  oauth:
    accounts: first@example.com,second@example.com
```

The supplied configuration also accepts an environment variable:

```bash
export GMAIL_ACCOUNTS='first@example.com,second@example.com'
```

The default remains `randall.burgess@ultiweb.com`. Use each account's primary Google email address, not a sending alias. Whitespace and duplicate addresses are normalized. `GMAIL_CLIENT_ID` and `GMAIL_CLIENT_SECRET` still configure the shared OAuth application.

When mailbox access runs, accounts are authorized sequentially. The console identifies the account being authorized and prints `OAuth consent required for Gmail account <email>` before opening a browser when consent is needed. Sign in as that account. The application checks the authorized email and rejects a different account.

Credentials are stored locally under `tokens/`, separately for each email address and permission set. Existing anonymous `user` / `user-*` token entries are left untouched and are not reused: each configured account needs consent on its first access after this change. Subsequent runs reuse its credentials and refresh tokens. Email identity permission is requested alongside Gmail permissions to verify the selected account.

Mail processing visits every configured account. Latest-mail analysis reads up to its requested limit **per account**. Messages and triage results include their owning account so label updates use the correct credentials. Label listing accepts an explicit account; calls without an account require exactly one configured account. Send/draft examples select the configured account matching the sender address.

The `production` Spring profile uses local Ollama for summarization and DeepInfra for classification. DeepInfra reads its API key from `DEEPINFRA_API_KEY`. No key is stored in configuration. DeepInfra uses its [OpenAI-compatible chat endpoint](https://docs.deepinfra.com/chat/overview) through Spring AI; an OpenAI account or key is not needed. Email subject, sender, and normalized visible body text go to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`) for summarization. HTML mail is converted to bounded plain text while preserving links, lists, image alternative text, and table cell relationships; scripts, styles, hidden elements, tracking query strings, attachments, and markup are excluded. Only the resulting summary is sent directly to DeepInfra for classification; the original email and metadata are not included in that request.

The default DeepInfra classification model is `meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo`. Set `DEEPINFRA_MODEL` to change it, or `DEEPINFRA_BASE_URL` to override the endpoint. These settings live in `src/main/resources/application-deepinfra.yml`.

Ollama must be running locally with the configured `OLLAMA_MODEL` (default `llama3.2:3b`) available. For the default model:

```bash
ollama pull llama3.2:3b
```

The safe default profile is `dev`: polling is disabled, all inference uses Ollama, and persistence uses `data/job-search-dev.db`. Start it with:

```bash
./gradlew bootRun
```

Production must be selected explicitly. It enables Gmail polling, writes `data/job-search.db`, and activates the `deepinfra` provider profile:

```bash
SPRING_PROFILES_ACTIVE=production ./gradlew bootRun
```

To run the production workflow while keeping both inference stages local:

```bash
SPRING_PROFILES_ACTIVE=local-production ./gradlew bootRun
```

The `test` profile disables polling and uses in-memory SQLite. The lower-level `ollama` and `deepinfra` profiles only select the classifier provider and are composed by the runtime profiles; they do not independently enable production behavior. All profiles use the same classification categories and prompts. Credentials remain environment variables rather than profile-file values.

The exact Gmail labels are `Dev_Jobs`, `Architect_Jobs`, `Management_Jobs`, `Misc_Jobs`, `Tech_News_Publications`, `General_News_Publications`, `Promotions_Commercial`, `Receipts`, `Delivery_Notification`, `Security_Alert`, `Personal`, and `Social_Media`. `EmailTag` is the source of truth for these names. The classification prompt lives in `src/main/resources/prompts/email-tag-system-prompt.txt`; its required `{{SUPPORTED_LABELS}}` placeholder is expanded from the enum at startup. Set `EMAIL_TAG_SYSTEM_PROMPT_LOCATION` to another Spring resource location such as `file:/path/to/email-tag-prompt.txt` to edit the prompt outside the application, retaining that placeholder. The prompt includes definitions, synthetic examples, and unmatched examples. Job labels require explicit recruitment evidence: `Management_Jobs` covers explicit technology/product delivery management, while other concrete occupations use `Misc_Jobs`. Delivery-only updates and account-security notices have their own labels. `NONE` leaves unsupported, ambiguous, or insufficiently described mail unlabeled. Responses may end with a plain, quoted, Markdown-wrapped, or explicitly prefixed label (for example, `Recommended tag: Receipts`); conflicting labels are rejected. Recommendations are logged as `recommendedLabel` before Gmail writes, including `NONE`. Invalid model outputs fail for retry rather than silently marking an email processed. Existing Gmail labels with older names are not renamed or removed.

The application stays running with an embedded HTTP server on port 8080 and logs startup to the console. Open `http://localhost:8080/` for the job-search dashboard. It loads persisted architect jobs immediately, defaulting to newest first, with options to sort oldest first or by title/company. The detail view includes inferred job information and renders the stored email HTML in a sandboxed frame. Select opportunities and choose **Save selections & prepare replies**; unsaved selection changes from every visited page are submitted together. DeepInfra creates a concise response using the original stored email; set `DEEPINFRA_API_KEY` to enable response generation in development or production. Generated response text is saved to the `responses` table in both profiles and appears on the opportunity card when ready. In production, the agent creates a Gmail draft and records its ID and status; it never sends the message. In `dev`, it stores the generated response in `data/job-search-dev.db` without creating a Gmail draft. The development dashboard has an explicit import action that summarizes and classifies recent inbox emails and also includes messages already labeled `Architect_Jobs`, stopping after it adds or enriches up to 10 architect opportunities per import. Only architect opportunities are copied into the development database; Gmail labels are not changed and polling remains disabled. Set `SERVER_PORT` to change the port; stop the application with Ctrl+C.

On startup, a background job checks inbox messages from the last 14 days that do not have the internal `Email_Management_Processed` label. It summarizes them locally, sends the summaries to the configured classifier, applies matching convenience labels, and finally applies the processed label. This durable Gmail marker prevents repeat inference after an application restart, including for messages classified as `NONE`. A failure before the processed marker is applied leaves the message eligible for retry. The job starts immediately and waits five minutes after each completed poll before starting another; polls do not overlap. The console logs account checks, inference requests, content lengths, applied labels, skipped messages, and failures without logging complete bodies or summaries.

Only `Architect_Jobs` messages are stored in SQLite. Production uses `data/job-search.db`, which can be overridden with `JOB_SEARCH_DATABASE_URL`, for example `jdbc:sqlite:/absolute/path/job-search.db`; development uses the ignored `data/job-search-dev.db`. Stored positions retain their source account/message identity, subject, sender, received time, classifier summary, and complete normalized visible email text. The `(source_account, source_message_id)` key prevents duplicates, and persistence never deletes existing job, recruiter, or response information. SQLite uses WAL mode and a busy timeout so the future dashboard can read while ingestion writes.

Manually applying `Architect_Jobs` in Gmail also schedules that message for persistence, regardless of age or its earlier AI classification. The agent searches for `Architect_Jobs` messages without the internal `Architect_Jobs_Persisted` marker, summarizes and stores any missing position, and then applies the persistence and processed markers. Existing database records are retained if labels are later removed or classifications change.

Configure polling in `gmail.polling` or through these environment variables:

```bash
export GMAIL_POLL_INTERVAL=PT5M
export GMAIL_POLLING_ENABLED=true
export GMAIL_INITIAL_LOOKBACK_DAYS=14
```

Use `GMAIL_POLLING_ENABLED=false` to run only the web server. `GMAIL_INITIAL_LOOKBACK_DAYS` accepts 1–365 days and controls the initial catch-up window; the processed Gmail label makes later polls incremental. Normal tests explicitly disable real polling or use mocks and in-memory SQLite databases.

Run isolated checks with:

```bash
./gradlew check
```

Real Gmail/Ollama analysis remains opt-in, explicitly selects the `ollama` profile, and does not apply labels:

```bash
RUN_GMAIL_OLLAMA_INTEGRATION_TEST=true ./gradlew integrationTest --tests com.ultiweb.jobs.svc.EmailTriageIntegrationTest
```

To run real Gmail/DeepInfra analysis:

```bash
RUN_GMAIL_DEEPINFRA_INTEGRATION_TEST=true ./gradlew integrationTest --tests com.ultiweb.jobs.svc.DeepInfraEmailTriageIntegrationTest --rerun-tasks
```

This requires a running local Ollama with `OLLAMA_MODEL` available, plus `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, and `DEEPINFRA_API_KEY`. `GMAIL_ACCOUNTS` selects the accounts; `DEEPINFRA_MODEL` overrides the configured model. The test disables background polling and uses virtual threads with the configured inference limits to analyze up to 50 latest emails per account, with one local Ollama summary request and one direct DeepInfra classification request per email. For manual classification review, the explicitly opt-in test logs a single-line bounded subject, generated summary, and recommended label, including `NONE`; it never logs the full body and does not modify messages or labels. It fails if no messages are available or a summary is missing or blank. The opt-in flag enables console output; `--rerun-tasks` ensures Gradle runs it again even if previous test results are up to date. Normal checks skip this live test.

Integration tests print the OAuth URL for manual approval instead of launching a desktop browser. Open the URL in your browser, sign in as the account shown, and finish consent. The test waits for the localhost callback and then resumes automatically; pressing Enter in the terminal is not required. Cached credentials skip this approval step.

To evaluate DeepInfra classification independently using 27 synthetic regression summaries (all twelve categories, unmatched cases, and an embedded instruction):

```bash
RUN_DEEPINFRA_CLASSIFICATION_INTEGRATION_TEST=true ./gradlew integrationTest --tests com.ultiweb.jobs.svc.ai.DeepInfraClassificationIntegrationTest --rerun-tasks
```

This opt-in test uses `DEEPINFRA_API_KEY` and requires neither Gmail access nor a running Ollama server. It makes real classification requests and asserts each expected label; it does not establish accuracy on your mailbox. Normal checks skip it.

Do not commit credentials or the local `tokens/` directory.

Triage runs email inference concurrently on Java 21 virtual threads, preserving result order and serial Gmail writes. Local Ollama calls share a concurrency limit of 2 (`OLLAMA_MAX_CONCURRENT_REQUESTS`). DeepInfra calls share a separate limit of 8 (`DEEPINFRA_MAX_CONCURRENT_REQUESTS`, allowed range 1–200), including time spent retrying. [DeepInfra's published quota](https://docs.deepinfra.com/account/rate-limits) is 200 concurrent requests per model per account, not a requests-per-minute allowance. These limits apply per application instance; divide the account budget across instances and other clients. HTTP 429 and transient server failures receive at most two retries with exponential delays starting at two seconds; other client errors are not retried. Failed messages remain eligible for the next poll.
