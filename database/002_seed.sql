SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @workspace_id UNIQUEIDENTIFIER = '10000000-0000-0000-0000-000000000001';
DECLARE @admin_user_id UNIQUEIDENTIFIER = '20000000-0000-0000-0000-000000000001';
DECLARE @admin_role_id UNIQUEIDENTIFIER = '30000000-0000-0000-0000-000000000001';
DECLARE @analyst_role_id UNIQUEIDENTIFIER = '30000000-0000-0000-0000-000000000002';
DECLARE @viewer_role_id UNIQUEIDENTIFIER = '30000000-0000-0000-0000-000000000003';
DECLARE @source_id UNIQUEIDENTIFIER = '40000000-0000-0000-0000-000000000001';
DECLARE @metric_id UNIQUEIDENTIFIER = '50000000-0000-0000-0000-000000000001';
DECLARE @conversation_id UNIQUEIDENTIFIER = '60000000-0000-0000-0000-000000000001';
DECLARE @report_id UNIQUEIDENTIFIER = '70000000-0000-0000-0000-000000000001';

BEGIN TRY
    BEGIN TRANSACTION;

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.workspaces WHERE id = @workspace_id)
        INSERT toscana_insights.workspaces (id, name, slug) VALUES (@workspace_id, N'Demo Workspace', N'demo');

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.users WHERE id = @admin_user_id)
        INSERT toscana_insights.users (id, workspace_id, email, display_name, status)
        VALUES (@admin_user_id, @workspace_id, N'admin@toscana-insights.local', N'Demo Admin', 'active');

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.roles WHERE id = @admin_role_id)
        INSERT toscana_insights.roles (id, workspace_id, name, description, is_system)
        VALUES (@admin_role_id, @workspace_id, N'Admin', N'Full workspace administration', 1);
    IF NOT EXISTS (SELECT 1 FROM toscana_insights.roles WHERE id = @analyst_role_id)
        INSERT toscana_insights.roles (id, workspace_id, name, description, is_system)
        VALUES (@analyst_role_id, @workspace_id, N'Analyst', N'Create metrics, conversations, and reports', 1);
    IF NOT EXISTS (SELECT 1 FROM toscana_insights.roles WHERE id = @viewer_role_id)
        INSERT toscana_insights.roles (id, workspace_id, name, description, is_system)
        VALUES (@viewer_role_id, @workspace_id, N'Viewer', N'View published analytics', 1);

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.user_roles WHERE user_id = @admin_user_id AND role_id = @admin_role_id)
        INSERT toscana_insights.user_roles (user_id, role_id) VALUES (@admin_user_id, @admin_role_id);

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.data_sources WHERE id = @source_id)
        INSERT toscana_insights.data_sources (id, workspace_id, name, source_type, status, config_json, created_by)
        VALUES (@source_id, @workspace_id, N'Sample SQL Server', 'sqlserver', 'draft', N'{"database":"sample","schema":"dbo"}', @admin_user_id);

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.semantic_metrics WHERE id = @metric_id)
        INSERT toscana_insights.semantic_metrics
            (id, workspace_id, data_source_id, name, key_name, description, sql_expression, aggregation, value_type, status, created_by)
        VALUES
            (@metric_id, @workspace_id, @source_id, N'Total Revenue', 'total_revenue', N'Sum of completed order revenue', N'order_total', 'sum', 'currency', 'draft', @admin_user_id);

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.conversations WHERE id = @conversation_id)
        INSERT toscana_insights.conversations (id, workspace_id, user_id, title)
        VALUES (@conversation_id, @workspace_id, @admin_user_id, N'Welcome conversation');

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.messages WHERE conversation_id = @conversation_id AND sender_type = 'assistant')
        INSERT toscana_insights.messages (conversation_id, sender_type, content, metadata_json)
        VALUES (@conversation_id, 'assistant', N'Connect a data source and ask a business question to begin.', N'{"seeded":true}');

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.reports WHERE id = @report_id)
        INSERT toscana_insights.reports (id, workspace_id, name, description, definition_json, status, created_by)
        VALUES (@report_id, @workspace_id, N'Executive Overview', N'Sample dashboard shell', N'{"layout":[],"filters":[]}', 'draft', @admin_user_id);

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.ingestion_runs WHERE data_source_id = @source_id AND details_json = N'{"seeded":true}')
        INSERT toscana_insights.ingestion_runs (workspace_id, data_source_id, status, trigger_type, details_json)
        VALUES (@workspace_id, @source_id, 'queued', 'manual', N'{"seeded":true}');

    IF NOT EXISTS (SELECT 1 FROM toscana_insights.audit_events WHERE workspace_id = @workspace_id AND event_type = 'workspace.seeded')
        INSERT toscana_insights.audit_events (workspace_id, actor_user_id, event_type, entity_type, entity_id, details_json)
        VALUES (@workspace_id, @admin_user_id, 'workspace.seeded', 'workspace', CONVERT(NVARCHAR(100), @workspace_id), N'{"source":"002_seed.sql"}');

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
