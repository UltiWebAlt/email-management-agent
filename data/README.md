# Local job-search databases

This folder holds private SQLite databases used by the application:

- `job-search.db`: production and local-production profiles. `JOB_SEARCH_DATABASE_URL` can override its location.
- `job-search-dev.db`: development profile.

The databases can contain personal email data: account addresses, senders, subjects, message bodies, summaries, recruiter details, and generated replies. Database files, backups named `job-search*.db*`, and SQLite WAL/SHM companion files are ignored by Git. Commit only this documentation and the SQL schema files; do not add database exports or personal seed data.

## Create an empty database

The application automatically creates and upgrades its schema on startup through [JobSearchDatabaseInitializer](../src/main/java/com/ultiweb/jobs/svc/persistence/JobSearchDatabaseInitializer.java). Manual creation is optional.

To create a new production database with the SQLite command-line tool, run these commands from the repository root:

```bash
sqlite3 -bail data/job-search.db < data/create-tables.sql
sqlite3 -bail data/job-search.db < data/create-indexes.sql
```

For development, use `data/job-search-dev.db` as the database filename in both commands.

`create-tables.sql` creates the `recruiters`, `positions`, and `responses` tables with their foreign keys, defaults, and response-type constraint. It enables WAL mode and sets a busy timeout. Foreign-key enforcement and the busy timeout apply to the current connection; the application also configures them at startup. `create-indexes.sql` creates the lookup indexes and the unique source-account/message index that prevents duplicate imported emails.

Both scripts use `IF NOT EXISTS` and can be rerun against a current schema. They contain no email records and do not modify existing records. They are creation scripts, not migration scripts: for older databases with missing columns, let the application run its startup migrations first. When changing the application schema, update these scripts to match.
