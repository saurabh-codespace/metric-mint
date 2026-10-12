SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRY
    BEGIN TRANSACTION;

    IF SCHEMA_ID(N'toscana_insights') IS NULL
        EXEC(N'CREATE SCHEMA toscana_insights AUTHORIZATION dbo;');

    IF OBJECT_ID(N'toscana_insights.workspaces', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.workspaces (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_workspaces PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            name NVARCHAR(200) NOT NULL,
            slug NVARCHAR(100) NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_workspaces_status DEFAULT 'active',
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_workspaces_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_workspaces_updated_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT UQ_workspaces_slug UNIQUE (slug),
            CONSTRAINT CK_workspaces_status CHECK (status IN ('active', 'suspended', 'archived'))
        );
    END;

    IF OBJECT_ID(N'toscana_insights.users', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.users (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_users PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            email NVARCHAR(320) NOT NULL,
            display_name NVARCHAR(200) NOT NULL,
            password_hash NVARCHAR(500) NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_users_status DEFAULT 'active',
            last_login_at DATETIME2(3) NULL,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_users_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_users_updated_at DEFAULT SYSUTCDATETIME(),
            deleted_at DATETIME2(3) NULL,
            CONSTRAINT FK_users_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT CK_users_status CHECK (status IN ('invited', 'active', 'disabled'))
        );
    END;

    IF OBJECT_ID(N'toscana_insights.roles', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.roles (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_roles PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            name NVARCHAR(100) NOT NULL,
            description NVARCHAR(500) NULL,
            is_system BIT NOT NULL CONSTRAINT DF_roles_is_system DEFAULT 0,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_roles_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_roles_updated_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_roles_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT UQ_roles_workspace_name UNIQUE (workspace_id, name)
        );
    END;

    IF OBJECT_ID(N'toscana_insights.user_roles', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.user_roles (
            user_id UNIQUEIDENTIFIER NOT NULL,
            role_id UNIQUEIDENTIFIER NOT NULL,
            assigned_at DATETIME2(3) NOT NULL CONSTRAINT DF_user_roles_assigned_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT PK_user_roles PRIMARY KEY (user_id, role_id),
            CONSTRAINT FK_user_roles_user FOREIGN KEY (user_id) REFERENCES toscana_insights.users(id) ON DELETE CASCADE,
            CONSTRAINT FK_user_roles_role FOREIGN KEY (role_id) REFERENCES toscana_insights.roles(id) ON DELETE CASCADE
        );
    END;

    IF OBJECT_ID(N'toscana_insights.data_sources', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.data_sources (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_data_sources PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            name NVARCHAR(200) NOT NULL,
            source_type VARCHAR(30) NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_data_sources_status DEFAULT 'draft',
            config_json NVARCHAR(MAX) NOT NULL CONSTRAINT DF_data_sources_config DEFAULT N'{}',
            secret_reference NVARCHAR(500) NULL,
            last_tested_at DATETIME2(3) NULL,
            created_by UNIQUEIDENTIFIER NULL,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_data_sources_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_data_sources_updated_at DEFAULT SYSUTCDATETIME(),
            deleted_at DATETIME2(3) NULL,
            CONSTRAINT FK_data_sources_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_data_sources_created_by FOREIGN KEY (created_by) REFERENCES toscana_insights.users(id),
            CONSTRAINT CK_data_sources_type CHECK (source_type IN ('sqlserver', 'postgresql', 'mysql', 'api', 'file')),
            CONSTRAINT CK_data_sources_status CHECK (status IN ('draft', 'active', 'error', 'disabled')),
            CONSTRAINT CK_data_sources_config_json CHECK (ISJSON(config_json) = 1)
        );
    END;

    IF OBJECT_ID(N'toscana_insights.semantic_metrics', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.semantic_metrics (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_semantic_metrics PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            data_source_id UNIQUEIDENTIFIER NOT NULL,
            name NVARCHAR(200) NOT NULL,
            key_name VARCHAR(100) NOT NULL,
            description NVARCHAR(1000) NULL,
            sql_expression NVARCHAR(MAX) NOT NULL,
            aggregation VARCHAR(20) NOT NULL,
            value_type VARCHAR(20) NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_semantic_metrics_status DEFAULT 'draft',
            created_by UNIQUEIDENTIFIER NULL,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_semantic_metrics_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_semantic_metrics_updated_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_semantic_metrics_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_semantic_metrics_data_source FOREIGN KEY (data_source_id) REFERENCES toscana_insights.data_sources(id),
            CONSTRAINT FK_semantic_metrics_created_by FOREIGN KEY (created_by) REFERENCES toscana_insights.users(id),
            CONSTRAINT UQ_semantic_metrics_workspace_key UNIQUE (workspace_id, key_name),
            CONSTRAINT CK_semantic_metrics_aggregation CHECK (aggregation IN ('sum', 'count', 'count_distinct', 'average', 'min', 'max', 'none')),
            CONSTRAINT CK_semantic_metrics_value_type CHECK (value_type IN ('number', 'currency', 'percentage', 'duration')),
            CONSTRAINT CK_semantic_metrics_status CHECK (status IN ('draft', 'published', 'archived'))
        );
    END;

    IF OBJECT_ID(N'toscana_insights.conversations', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.conversations (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_conversations PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            user_id UNIQUEIDENTIFIER NOT NULL,
            title NVARCHAR(300) NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_conversations_status DEFAULT 'active',
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_conversations_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_conversations_updated_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_conversations_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_conversations_user FOREIGN KEY (user_id) REFERENCES toscana_insights.users(id),
            CONSTRAINT CK_conversations_status CHECK (status IN ('active', 'archived'))
        );
    END;

    IF OBJECT_ID(N'toscana_insights.messages', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.messages (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_messages PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            conversation_id UNIQUEIDENTIFIER NOT NULL,
            sender_type VARCHAR(20) NOT NULL,
            content NVARCHAR(MAX) NOT NULL,
            generated_sql NVARCHAR(MAX) NULL,
            result_json NVARCHAR(MAX) NULL,
            metadata_json NVARCHAR(MAX) NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_messages_status DEFAULT 'completed',
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_messages_created_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_messages_conversation FOREIGN KEY (conversation_id) REFERENCES toscana_insights.conversations(id) ON DELETE CASCADE,
            CONSTRAINT CK_messages_sender_type CHECK (sender_type IN ('user', 'assistant', 'system')),
            CONSTRAINT CK_messages_status CHECK (status IN ('pending', 'completed', 'failed')),
            CONSTRAINT CK_messages_result_json CHECK (result_json IS NULL OR ISJSON(result_json) = 1),
            CONSTRAINT CK_messages_metadata_json CHECK (metadata_json IS NULL OR ISJSON(metadata_json) = 1)
        );
    END;

    IF OBJECT_ID(N'toscana_insights.reports', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.reports (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_reports PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            name NVARCHAR(200) NOT NULL,
            description NVARCHAR(1000) NULL,
            definition_json NVARCHAR(MAX) NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_reports_status DEFAULT 'draft',
            created_by UNIQUEIDENTIFIER NULL,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_reports_created_at DEFAULT SYSUTCDATETIME(),
            updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_reports_updated_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_reports_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_reports_created_by FOREIGN KEY (created_by) REFERENCES toscana_insights.users(id),
            CONSTRAINT CK_reports_definition_json CHECK (ISJSON(definition_json) = 1),
            CONSTRAINT CK_reports_status CHECK (status IN ('draft', 'published', 'archived'))
        );
    END;

    IF OBJECT_ID(N'toscana_insights.ingestion_runs', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.ingestion_runs (
            id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_ingestion_runs PRIMARY KEY DEFAULT NEWSEQUENTIALID(),
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            data_source_id UNIQUEIDENTIFIER NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT DF_ingestion_runs_status DEFAULT 'queued',
            trigger_type VARCHAR(20) NOT NULL CONSTRAINT DF_ingestion_runs_trigger DEFAULT 'manual',
            rows_processed BIGINT NOT NULL CONSTRAINT DF_ingestion_runs_rows DEFAULT 0,
            error_message NVARCHAR(2000) NULL,
            details_json NVARCHAR(MAX) NULL,
            started_at DATETIME2(3) NULL,
            completed_at DATETIME2(3) NULL,
            created_at DATETIME2(3) NOT NULL CONSTRAINT DF_ingestion_runs_created_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_ingestion_runs_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_ingestion_runs_data_source FOREIGN KEY (data_source_id) REFERENCES toscana_insights.data_sources(id),
            CONSTRAINT CK_ingestion_runs_status CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
            CONSTRAINT CK_ingestion_runs_trigger CHECK (trigger_type IN ('manual', 'scheduled', 'api')),
            CONSTRAINT CK_ingestion_runs_rows CHECK (rows_processed >= 0),
            CONSTRAINT CK_ingestion_runs_details_json CHECK (details_json IS NULL OR ISJSON(details_json) = 1),
            CONSTRAINT CK_ingestion_runs_times CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
        );
    END;

    IF OBJECT_ID(N'toscana_insights.audit_events', N'U') IS NULL
    BEGIN
        CREATE TABLE toscana_insights.audit_events (
            id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_audit_events PRIMARY KEY,
            workspace_id UNIQUEIDENTIFIER NOT NULL,
            actor_user_id UNIQUEIDENTIFIER NULL,
            event_type VARCHAR(100) NOT NULL,
            entity_type VARCHAR(100) NULL,
            entity_id NVARCHAR(100) NULL,
            ip_address VARCHAR(45) NULL,
            details_json NVARCHAR(MAX) NULL,
            occurred_at DATETIME2(3) NOT NULL CONSTRAINT DF_audit_events_occurred_at DEFAULT SYSUTCDATETIME(),
            CONSTRAINT FK_audit_events_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.workspaces(id),
            CONSTRAINT FK_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES toscana_insights.users(id),
            CONSTRAINT CK_audit_events_details_json CHECK (details_json IS NULL OR ISJSON(details_json) = 1)
        );
    END;

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_users_workspace_email_active' AND object_id = OBJECT_ID(N'toscana_insights.users'))
        CREATE UNIQUE INDEX UX_users_workspace_email_active ON toscana_insights.users(workspace_id, email) WHERE deleted_at IS NULL;
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_user_roles_role_id' AND object_id = OBJECT_ID(N'toscana_insights.user_roles'))
        CREATE INDEX IX_user_roles_role_id ON toscana_insights.user_roles(role_id);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_data_sources_workspace_name_active' AND object_id = OBJECT_ID(N'toscana_insights.data_sources'))
        CREATE UNIQUE INDEX UX_data_sources_workspace_name_active ON toscana_insights.data_sources(workspace_id, name) WHERE deleted_at IS NULL;
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_semantic_metrics_data_source_status' AND object_id = OBJECT_ID(N'toscana_insights.semantic_metrics'))
        CREATE INDEX IX_semantic_metrics_data_source_status ON toscana_insights.semantic_metrics(data_source_id, status);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_conversations_user_updated' AND object_id = OBJECT_ID(N'toscana_insights.conversations'))
        CREATE INDEX IX_conversations_user_updated ON toscana_insights.conversations(user_id, updated_at DESC);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_messages_conversation_created' AND object_id = OBJECT_ID(N'toscana_insights.messages'))
        CREATE INDEX IX_messages_conversation_created ON toscana_insights.messages(conversation_id, created_at);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_reports_workspace_status' AND object_id = OBJECT_ID(N'toscana_insights.reports'))
        CREATE INDEX IX_reports_workspace_status ON toscana_insights.reports(workspace_id, status, updated_at DESC);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ingestion_runs_source_created' AND object_id = OBJECT_ID(N'toscana_insights.ingestion_runs'))
        CREATE INDEX IX_ingestion_runs_source_created ON toscana_insights.ingestion_runs(data_source_id, created_at DESC);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_audit_events_workspace_occurred' AND object_id = OBJECT_ID(N'toscana_insights.audit_events'))
        CREATE INDEX IX_audit_events_workspace_occurred ON toscana_insights.audit_events(workspace_id, occurred_at DESC);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
