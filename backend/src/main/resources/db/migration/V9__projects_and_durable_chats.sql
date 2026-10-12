CREATE TABLE toscana_insights.app_projects (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    workspace_id uniqueidentifier NOT NULL,
    owner_name nvarchar(200) NOT NULL,
    name nvarchar(80) NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    updated_at datetimeoffset(6) NOT NULL,
    CONSTRAINT fk_app_projects_workspace FOREIGN KEY (workspace_id) REFERENCES toscana_insights.app_workspaces(id)
);
CREATE INDEX ix_app_projects_owner ON toscana_insights.app_projects(workspace_id, owner_name, created_at DESC);

ALTER TABLE toscana_insights.app_conversations ADD project_id uniqueidentifier NULL;
ALTER TABLE toscana_insights.app_conversations ADD CONSTRAINT fk_app_conversations_project FOREIGN KEY (project_id) REFERENCES toscana_insights.app_projects(id);
ALTER TABLE toscana_insights.app_conversations ALTER COLUMN owner_name nvarchar(200) NULL;
ALTER TABLE toscana_insights.app_questions ALTER COLUMN question_text nvarchar(4000) NOT NULL;
ALTER TABLE toscana_insights.app_questions ALTER COLUMN answer_text nvarchar(max) NOT NULL;
ALTER TABLE toscana_insights.app_questions ADD status nvarchar(12) NOT NULL CONSTRAINT df_app_questions_status DEFAULT 'complete', error nvarchar(500) NULL;
