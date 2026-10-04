-- Run after create-tables.sql.
BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uq_positions_source_message
ON positions(source_account, source_message_id)
WHERE source_account IS NOT NULL AND source_message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_positions_date_posted ON positions(date_posted);

CREATE INDEX IF NOT EXISTS idx_positions_created_at ON positions(created_at);

CREATE INDEX IF NOT EXISTS idx_positions_respond_to ON positions(respond_to);

CREATE INDEX IF NOT EXISTS idx_positions_recruiter_id ON positions(recruiter_id);

CREATE INDEX IF NOT EXISTS idx_responses_position_id ON responses(position_id);

CREATE INDEX IF NOT EXISTS idx_responses_status ON responses(response_status);

COMMIT;
