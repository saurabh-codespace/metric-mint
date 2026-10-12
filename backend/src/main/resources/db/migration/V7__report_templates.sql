CREATE TABLE toscana_insights.app_report_templates (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    owner_name nvarchar(200) NOT NULL,
    name nvarchar(120) NOT NULL,
    definition nvarchar(max) NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL,
    CONSTRAINT fk_report_templates_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.app_workspaces(id)
);
CREATE INDEX ix_report_templates_workspace ON toscana_insights.app_report_templates (workspace_id, name);
