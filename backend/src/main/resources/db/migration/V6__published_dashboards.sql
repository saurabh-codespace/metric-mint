CREATE TABLE toscana_insights.app_published_dashboards (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    owner_name nvarchar(200) NOT NULL,
    name nvarchar(120) NOT NULL,
    kind nvarchar(20) NOT NULL,
    report_id nvarchar(40) NULL,
    days int NOT NULL,
    unit nvarchar(30) NOT NULL,
    chart nvarchar(20) NOT NULL,
    token varchar(43) COLLATE Latin1_General_100_BIN2 NOT NULL UNIQUE,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL,
    CONSTRAINT fk_published_dashboard_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.app_workspaces(id)
);

CREATE INDEX ix_published_dashboard_workspace ON toscana_insights.app_published_dashboards(workspace_id, created_at DESC);
