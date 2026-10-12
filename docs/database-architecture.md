# MVP Database Architecture

Metric Mint uses the existing SQL Server database with the `toscana_insights` schema. Every business record is scoped to a workspace so the backend can enforce tenant isolation.

## Main areas

- **Identity and access:** `workspaces`, `users`, `roles`, and `user_roles`.
- **Data connectivity:** `data_sources` stores non-secret connector settings. `secret_reference` points to an external secret store; credentials must never be stored in `config_json`.
- **Governed analytics:** `semantic_metrics` defines approved calculations for AI and reports.
- **AI conversations:** `conversations` and `messages` retain prompts, answers, generated SQL, and compact result metadata.
- **Reporting:** `reports` stores report layout and filter definitions as JSON.
- **Operations:** `ingestion_runs` tracks imports and refreshes; `audit_events` is an append-only activity trail.

IDs are UUIDs except the high-volume audit log, which uses an identity key. Timestamps are UTC `datetime2(3)`. Foreign keys protect relationships, check constraints restrict states, and indexes cover common workspace, user, conversation, ingestion, and audit queries.

The bootstrap scripts are idempotent: they create missing objects and seed rows only when absent. Future structural changes should be added as new numbered migration files instead of editing an already deployed migration.
