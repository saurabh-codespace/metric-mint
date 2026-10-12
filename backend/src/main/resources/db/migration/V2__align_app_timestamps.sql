DROP INDEX IF EXISTS ix_app_audit_events_created_at ON toscana_insights.app_audit_events;

ALTER TABLE toscana_insights.data_source_connections ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.data_source_connections ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_semantic_metrics ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_semantic_metrics ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_conversations ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_conversations ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_questions ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_questions ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_reports ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_reports ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_audit_events ALTER COLUMN created_at datetimeoffset(6) NOT NULL;
ALTER TABLE toscana_insights.app_audit_events ALTER COLUMN updated_at datetimeoffset(6) NOT NULL;

CREATE INDEX ix_app_audit_events_created_at ON toscana_insights.app_audit_events(created_at DESC);
