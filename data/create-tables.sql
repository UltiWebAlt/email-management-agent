-- SQLite tables for a new job-search database. No personal or seed data.
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = WAL;

BEGIN;

CREATE TABLE IF NOT EXISTS recruiters (
	id INTEGER PRIMARY KEY,
	company VARCHAR(255),
	created_at TIMESTAMP,
	email VARCHAR(255) NOT NULL UNIQUE,
	linkedin_profile VARCHAR(255),
	name VARCHAR(255),
	phone VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS positions (
	id INTEGER PRIMARY KEY,
	company VARCHAR(255),
	created_at TIMESTAMP,
	date_posted TIMESTAMP,
	description TEXT,
	is_remote BOOLEAN,
	location VARCHAR(255),
	requirements TEXT,
	salary_range VARCHAR(255),
	title VARCHAR(255) NOT NULL,
	recruiter_id BIGINT NOT NULL,
	source_account TEXT,
	source_message_id TEXT,
	source_subject TEXT,
	source_sender TEXT,
	source_received_at TEXT,
	summary TEXT,
	html_body TEXT,
	respond_to INTEGER NOT NULL DEFAULT 0,
	FOREIGN KEY (recruiter_id) REFERENCES recruiters(id)
);

CREATE TABLE IF NOT EXISTS responses (
	id INTEGER PRIMARY KEY,
	created_at TIMESTAMP,
	follow_up_needed BOOLEAN,
	response_content TEXT,
	response_type VARCHAR(255) CHECK (response_type IN (
	'INITIAL_RESPONSE', 'FOLLOW_UP', 'INTERVIEW_CONFIRMATION', 'THANK_YOU', 'DECLINED')),
	response_status TEXT NOT NULL DEFAULT 'RECORDED',
	gmail_draft_id TEXT,
	updated_at TEXT,
	sent_at TIMESTAMP,
	position_id BIGINT NOT NULL,
	recruiter_id BIGINT NOT NULL,
	FOREIGN KEY (position_id) REFERENCES positions(id),
	FOREIGN KEY (recruiter_id) REFERENCES recruiters(id)
);

COMMIT;
