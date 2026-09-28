# Email Management Agent

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

Unread-mail processing visits every configured account. Latest-mail analysis reads up to its requested limit **per account**. Messages and triage results include their owning account so label updates use the correct credentials. Label listing accepts an explicit account; calls without an account require exactly one configured account. Send/draft examples select the configured account matching the sender address.

The default `deepinfra` Spring profile uses local Ollama for summarization and DeepInfra for classification. DeepInfra reads its API key from `DEEPINFRA_API_KEY`. No key is stored in configuration. DeepInfra uses its [OpenAI-compatible chat endpoint](https://docs.deepinfra.com/chat/overview) through Spring AI; an OpenAI account or key is not needed. Email subject, sender, and normalized visible body text go to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`) for summarization. HTML mail is converted to bounded plain text while preserving links, lists, image alternative text, and table cell relationships; scripts, styles, hidden elements, tracking query strings, attachments, and markup are excluded. Only the resulting summary is sent directly to DeepInfra for classification; the original email and metadata are not included in that request.

The default DeepInfra classification model is `meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo`. Set `DEEPINFRA_MODEL` to change it, or `DEEPINFRA_BASE_URL` to override the endpoint. These settings live in `src/main/resources/application-deepinfra.yml`.

Ollama must be running locally with the configured `OLLAMA_MODEL` (default `llama3.2:3b`) available. For the default model:

```bash
ollama pull llama3.2:3b
```

To use Ollama for both summarization and classification:

```bash
export SPRING_PROFILES_ACTIVE=ollama
```

Select the local-summary/DeepInfra-classification pipeline explicitly with `SPRING_PROFILES_ACTIVE=deepinfra`, or leave the active profile unset to use the default. The all-Ollama profile requires no DeepInfra key. Both profiles use the same classification categories and prompts.

The exact Gmail labels are `Dev_Jobs`, `Architect_Jobs`, `Management_Jobs`, `Misc_Jobs`, `Tech_News_Publications`, `General_News_Publications`, `Promotions_Commercial`, `Receipts`, `Delivery_Notification`, `Security_Alert`, `Personal`, and `Social_Media`. `EmailTag` is the source of truth for these names. The classification prompt lives in `src/main/resources/prompts/email-tag-system-prompt.txt`; its required `{{SUPPORTED_LABELS}}` placeholder is expanded from the enum at startup. Set `EMAIL_TAG_SYSTEM_PROMPT_LOCATION` to another Spring resource location such as `file:/path/to/email-tag-prompt.txt` to edit the prompt outside the application, retaining that placeholder. The prompt includes definitions, synthetic examples, and unmatched examples. Job labels require explicit recruitment evidence: `Management_Jobs` covers explicit technology/product delivery management, while other concrete occupations use `Misc_Jobs`. Delivery-only updates and account-security notices have their own labels. `NONE` leaves unsupported, ambiguous, or insufficiently described mail unlabeled. Responses may end with a plain, quoted, Markdown-wrapped, or explicitly prefixed label (for example, `Recommended tag: Receipts`); conflicting labels are rejected. Recommendations are logged as `recommendedLabel` before Gmail writes, including `NONE`. Invalid model outputs fail for retry rather than silently marking an email processed. Existing Gmail labels with older names are not renamed or removed.

Start the web application with `./gradlew bootRun`. It stays running with an embedded HTTP server on port 8080 and logs startup to the console. Set `SERVER_PORT` to change the port; stop it with Ctrl+C. No frontend routes have been added yet, so `/` returns HTTP 404.

On startup, a background job checks unread Gmail messages for every configured account, summarizes new messages locally and sends the summaries to the configured classifier for label recommendations, and applies matching Gmail labels. It starts immediately and waits five minutes after each completed poll before starting another; polls do not overlap. The console logs account checks, inference requests, content lengths, applied labels, skipped messages, and failures without logging complete bodies or summaries. A failed account or message is retried on the next poll while other work continues.

Configure polling in `gmail.polling` or through these environment variables:

```bash
export GMAIL_POLL_INTERVAL=PT5M
export GMAIL_POLLING_ENABLED=true
```

Use `GMAIL_POLLING_ENABLED=false` to run only the web server. Successfully analyzed messages (including those with no matching label) are remembered by account and message ID for the life of this process. They remain unread but are not sent for inference again during later polls. This tracking is in memory: restarting the app allows unread messages to be analyzed again. Normal tests explicitly disable real polling; scheduling tests use mocks.

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
