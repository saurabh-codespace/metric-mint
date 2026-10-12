-- Governed analytics foundation: workspaces, users/roles, source health, catalogue, metric versions,
-- row policies, ingestion runs. Tables are prefixed app_ to stay clear of the manual scripts under /database.

CREATE TABLE toscana_insights.app_workspaces (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    name nvarchar(120) NOT NULL,
    slug nvarchar(80) NOT NULL UNIQUE,
    max_queries_per_day int NOT NULL,
    max_rows int NOT NULL,
    query_timeout_seconds int NOT NULL,
    stale_after_minutes int NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL
);

CREATE TABLE toscana_insights.app_users (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    username nvarchar(200) NOT NULL UNIQUE,
    display_name nvarchar(120) NOT NULL,
    password_hash nvarchar(100) NOT NULL,
    role nvarchar(20) NOT NULL,
    enabled bit NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL,
    CONSTRAINT fk_app_users_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.app_workspaces(id)
);

INSERT INTO toscana_insights.app_workspaces (id, name, slug, max_queries_per_day, max_rows, query_timeout_seconds, stale_after_minutes, created_at, updated_at)
VALUES ('A0000000-0000-0000-0000-000000000001', 'Default workspace', 'default', 500, 10000, 15, 1440, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET());
GO

-- Data sources: tenant, encrypted secret, connector config, health and schedule.
ALTER TABLE toscana_insights.data_source_connections ADD
    workspace_id uniqueidentifier NULL,
    secret_cipher nvarchar(2000) NULL,
    config_json nvarchar(4000) NULL,
    last_tested_at datetimeoffset(6) NULL,
    last_test_message nvarchar(500) NULL,
    schedule_minutes int NULL,
    next_run_at datetimeoffset(6) NULL,
    last_success_at datetimeoffset(6) NULL,
    last_run_status nvarchar(20) NULL,
    last_error nvarchar(500) NULL,
    cursor_value nvarchar(255) NULL,
    last_checksum nvarchar(64) NULL;
GO
EXEC('UPDATE toscana_insights.data_source_connections SET workspace_id = ''A0000000-0000-0000-0000-000000000001''');
EXEC('ALTER TABLE toscana_insights.data_source_connections ALTER COLUMN workspace_id uniqueidentifier NOT NULL');
GO

-- Semantic metrics: binding to a catalogue table, approval state, versioning, verification.
ALTER TABLE toscana_insights.app_semantic_metrics ADD
    workspace_id uniqueidentifier NULL,
    table_id uniqueidentifier NULL,
    date_column nvarchar(128) NULL,
    synonyms nvarchar(500) NULL,
    baseline_value decimal(38,6) NULL,
    status nvarchar(20) NOT NULL CONSTRAINT df_app_metrics_status DEFAULT 'DRAFT',
    version int NOT NULL CONSTRAINT df_app_metrics_version DEFAULT 1,
    author_name nvarchar(100) NULL,
    approved_by nvarchar(100) NULL,
    approved_at datetimeoffset(6) NULL,
    verified bit NOT NULL CONSTRAINT df_app_metrics_verified DEFAULT 0,
    verified_by nvarchar(100) NULL,
    verified_at datetimeoffset(6) NULL;
GO
EXEC('UPDATE toscana_insights.app_semantic_metrics SET workspace_id = ''A0000000-0000-0000-0000-000000000001''');
EXEC('ALTER TABLE toscana_insights.app_semantic_metrics ALTER COLUMN workspace_id uniqueidentifier NOT NULL');
GO
DECLARE @uq nvarchar(200) = (
    SELECT TOP 1 kc.name FROM sys.key_constraints kc
    JOIN sys.tables t ON kc.parent_object_id = t.object_id
    JOIN sys.schemas s ON t.schema_id = s.schema_id
    WHERE s.name = 'toscana_insights' AND t.name = 'app_semantic_metrics' AND kc.type = 'UQ');
IF @uq IS NOT NULL EXEC('ALTER TABLE toscana_insights.app_semantic_metrics DROP CONSTRAINT [' + @uq + ']');
GO
CREATE UNIQUE INDEX ux_app_semantic_metrics_ws_key ON toscana_insights.app_semantic_metrics(workspace_id, key_name);

CREATE TABLE toscana_insights.app_metric_versions (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    metric_id uniqueidentifier NOT NULL,
    version_number int NOT NULL,
    status nvarchar(20) NOT NULL,
    changed_by nvarchar(100) NOT NULL,
    change_type nvarchar(20) NOT NULL,
    snapshot_json nvarchar(max) NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL
);
CREATE INDEX ix_app_metric_versions_metric ON toscana_insights.app_metric_versions(metric_id, created_at);

-- Conversations and reports: owner and tenant; wider payload columns for evidence and saved queries.
ALTER TABLE toscana_insights.app_conversations ADD workspace_id uniqueidentifier NULL, owner_name nvarchar(100) NULL;
GO
EXEC('UPDATE toscana_insights.app_conversations SET workspace_id = ''A0000000-0000-0000-0000-000000000001''');
EXEC('ALTER TABLE toscana_insights.app_conversations ALTER COLUMN workspace_id uniqueidentifier NOT NULL');
GO
ALTER TABLE toscana_insights.app_questions ALTER COLUMN result_json nvarchar(max) NOT NULL;

ALTER TABLE toscana_insights.app_reports ADD workspace_id uniqueidentifier NULL, owner_name nvarchar(100) NULL,
    shared bit NOT NULL CONSTRAINT df_app_reports_shared DEFAULT 0;
GO
EXEC('UPDATE toscana_insights.app_reports SET workspace_id = ''A0000000-0000-0000-0000-000000000001''');
EXEC('ALTER TABLE toscana_insights.app_reports ALTER COLUMN workspace_id uniqueidentifier NOT NULL');
ALTER TABLE toscana_insights.app_reports ALTER COLUMN configuration nvarchar(4000) NOT NULL;
GO

-- Audit: tenant scope and a covering index for investigations and daily query budgets.
ALTER TABLE toscana_insights.app_audit_events ADD workspace_id uniqueidentifier NULL;
GO
EXEC('UPDATE toscana_insights.app_audit_events SET workspace_id = ''A0000000-0000-0000-0000-000000000001''');
CREATE INDEX ix_app_audit_events_ws_action ON toscana_insights.app_audit_events(workspace_id, action, created_at DESC);
GO

CREATE TABLE toscana_insights.app_ingestion_runs (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    source_id uniqueidentifier NOT NULL,
    trigger_type nvarchar(20) NOT NULL,
    status nvarchar(20) NOT NULL,
    started_at datetimeoffset(6) NOT NULL,
    finished_at datetimeoffset(6) NULL,
    rows_loaded bigint NOT NULL,
    rows_rejected bigint NOT NULL,
    message nvarchar(500) NULL,
    table_name nvarchar(160) NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL
);
CREATE INDEX ix_app_ingestion_runs_source ON toscana_insights.app_ingestion_runs(source_id, started_at DESC);

CREATE TABLE toscana_insights.app_catalog_tables (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    source_id uniqueidentifier NOT NULL,
    name nvarchar(160) NOT NULL,
    physical_name nvarchar(200) NOT NULL,
    description nvarchar(500) NOT NULL,
    owner_name nvarchar(120) NOT NULL,
    columns_json nvarchar(max) NOT NULL,
    row_count bigint NOT NULL,
    last_loaded_at datetimeoffset(6) NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL,
    CONSTRAINT ux_app_catalog_tables_ws_name UNIQUE (workspace_id, name)
);

CREATE TABLE toscana_insights.app_catalog_relationships (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    from_table_id uniqueidentifier NOT NULL,
    from_column nvarchar(128) NOT NULL,
    to_table_id uniqueidentifier NOT NULL,
    to_column nvarchar(128) NOT NULL,
    cardinality nvarchar(20) NOT NULL,
    test_status nvarchar(20) NOT NULL,
    test_message nvarchar(500) NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL
);

CREATE TABLE toscana_insights.app_access_policies (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    table_id uniqueidentifier NOT NULL,
    column_name nvarchar(128) NOT NULL,
    match_value nvarchar(500) NOT NULL,
    role_name nvarchar(20) NULL,
    target_username nvarchar(200) NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL
);
