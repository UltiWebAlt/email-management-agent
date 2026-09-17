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

Inference defaults to the `deepinfra` Spring profile and reads its API key from `DEEPINFRA_API_KEY`. No key is stored in configuration. DeepInfra uses its [OpenAI-compatible chat endpoint](https://docs.deepinfra.com/chat/overview) through Spring AI; an OpenAI account or key is not needed. Requests go directly to DeepInfra; Ollama is not involved and does not need to be running.

The default DeepInfra model is `meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo`. Set `DEEPINFRA_MODEL` to change it, or `DEEPINFRA_BASE_URL` to override the endpoint. These settings live in `src/main/resources/application-deepinfra.yml`.

Switch back to Ollama with:

```bash
export SPRING_PROFILES_ACTIVE=ollama
```

Select DeepInfra explicitly with `SPRING_PROFILES_ACTIVE=deepinfra`, or leave the active profile unset to use the default. Ollama keeps its existing `OLLAMA_BASE_URL` and `OLLAMA_MODEL` settings and requires no DeepInfra key. Summary prompts, label recommendations, and mailbox processing are shared by both providers. With DeepInfra selected, inference requests send the email content to DeepInfra.

Start the web application with `./gradlew bootRun`. It stays running with an embedded HTTP server on port 8080 and logs startup to the console. Set `SERVER_PORT` to change the port; stop it with Ctrl+C. No frontend routes have been added yet, so `/` returns HTTP 404.

On startup, a background job checks unread Gmail messages for every configured account, sends new messages to the selected inference provider for summaries and label recommendations, and applies matching Gmail labels. It starts immediately and waits five minutes after each completed poll before starting another; polls do not overlap. The console logs account checks, inference requests, summaries, applied labels, skipped messages, and failures. A failed account or message is retried on the next poll while other work continues.

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

This requires `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, and `DEEPINFRA_API_KEY`. `GMAIL_ACCOUNTS` selects the accounts; `DEEPINFRA_MODEL` overrides the configured model. The test selects DeepInfra directly, disables background polling, and analyzes up to 50 latest emails per account (up to two inference requests per email). It logs summaries and recommended labels, including `NONE`, without modifying messages or labels. It fails if no messages are available or a summary is missing or blank. The opt-in flag enables console output; `--rerun-tasks` ensures Gradle runs it again even if previous test results are up to date. Normal checks skip this live test.

Do not commit credentials or the local `tokens/` directory.
