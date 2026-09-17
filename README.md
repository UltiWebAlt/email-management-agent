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

Run isolated checks with:

```bash
./gradlew check
```

Real Gmail/Ollama analysis remains opt-in and does not apply labels:

```bash
RUN_GMAIL_OLLAMA_INTEGRATION_TEST=true ./gradlew integrationTest --tests com.ultiweb.jobs.svc.EmailTriageIntegrationTest
```

Do not commit credentials or the local `tokens/` directory.
