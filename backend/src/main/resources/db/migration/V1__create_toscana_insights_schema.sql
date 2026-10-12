IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'toscana_insights')
    EXEC('CREATE SCHEMA toscana_insights');

CREATE TABLE toscana_insights.data_source_connections (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    name nvarchar(120) NOT NULL,
    type nvarchar(30) NOT NULL,
    host nvarchar(255) NOT NULL,
    port int NOT NULL,
    database_name nvarchar(120) NOT NULL,
    username nvarchar(120) NOT NULL,
    credential_reference nvarchar(255) NOT NULL,
    status nvarchar(20) NOT NULL,
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL
);

CREATE TABLE toscana_insights.app_semantic_metrics (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    name nvarchar(120) NOT NULL,
    key_name nvarchar(80) NOT NULL UNIQUE,
    description nvarchar(500) NOT NULL,
    aggregation nvarchar(30) NOT NULL,
    source_field nvarchar(255) NOT NULL,
    active bit NOT NULL,
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL
);

CREATE TABLE toscana_insights.app_conversations (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    title nvarchar(160) NOT NULL,
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL
);

CREATE TABLE toscana_insights.app_questions (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    conversation_id uniqueidentifier NOT NULL,
    question_text nvarchar(1000) NOT NULL,
    answer_text nvarchar(2000) NOT NULL,
    visualization nvarchar(30) NOT NULL,
    result_json nvarchar(4000) NOT NULL,
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL,
    CONSTRAINT fk_app_questions_conversation FOREIGN KEY (conversation_id) REFERENCES toscana_insights.app_conversations(id)
);

CREATE INDEX ix_app_questions_conversation_id ON toscana_insights.app_questions(conversation_id);

CREATE TABLE toscana_insights.app_reports (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    name nvarchar(120) NOT NULL,
    visualization nvarchar(30) NOT NULL,
    configuration nvarchar(2000) NOT NULL,
    favorite bit NOT NULL,
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL
);

CREATE TABLE toscana_insights.app_audit_events (
    id uniqueidentifier NOT NULL PRIMARY KEY,
    actor nvarchar(100) NOT NULL,
    action nvarchar(60) NOT NULL,
    resource_type nvarchar(60) NOT NULL,
    resource_id nvarchar(255),
    details nvarchar(1000),
    created_at datetime2 NOT NULL,
    updated_at datetime2 NOT NULL
);

CREATE INDEX ix_app_audit_events_created_at ON toscana_insights.app_audit_events(created_at DESC);
