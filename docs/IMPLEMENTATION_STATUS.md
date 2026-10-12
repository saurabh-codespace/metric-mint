# BRD implementation status (P0)

## Excel reports, publishing and UI refresh (10 October 2026)

- Added five reports from workbook rows 2, 3, 17, 18 and 40, backed by 40 synthetic SQL Server aggregate rows in Flyway V5. KPIs reconcile with their report rows; percentages and average durations use weighted totals.
- Added persistent executive/workbook dashboard publishing in V6, shareable 256-bit random tokens, mandatory sign-in and workspace isolation, saved filter/chart definitions, and publisher/admin revocation. Tokens and credentials are excluded from audit details.
- Added a modern report gallery/detail view, charts/table switching, formula-safe CSV exports, publishing management, Motion animations and responsive light/dark styling. Existing connected I2P report views remain available.
- Validation: 37 backend tests (including publication permissions, CORS and workbook reconciliation), five frontend data/CSV tests, frontend production build, live SQL Server V5/V6 migrations, five-report browser flow, token sign-in/reload/revocation, and executive filter persistence. Desktop/dark/mobile screenshots saved locally in `frontend/test-results/`.
- Boundaries: synthetic fixed snapshot, existing accounts in the same workspace only, no internet deployment, no generated AI-dashboard publication. Full accessibility/load audits and bundle splitting remain deferred; the production build reports a large JavaScript chunk.

## Executive dashboard follow-up (10 October 2026)

- Completed the database-backed sample dashboard: 5,130 synthetic invoices, eight KPIs, trends, business-unit breakdowns, filters, searchable/paginated invoices, and CSV export. The connected I2P views remain available through the view switch.
- Finished browser verification in Microsoft Edge at desktop and mobile widths, including login, authentication enforcement, KPI reconciliation, filters, chart switching, export, empty search, pagination, metric navigation, and dark theme. No browser runtime errors or mobile page overflow were detected.
- Fixed login input names, legacy KPI color collisions, and a stale generated Vite config that routed requests to the wrong backend port. Local defaults now use frontend 5173, backend 8090, and SQL Server 1433.
- Frontend production build and all three dashboard data tests pass. Existing backend test reports show 27 passing tests from the implementation run; the backend suite was not rerun during this UI/configuration follow-up.
- Sample data is a fixed 9 October 2026 snapshot, shared across authenticated workspaces. Full accessibility/load testing and frontend bundle splitting remain outside this follow-up; Vite reports a large JavaScript chunk.

## Original P0 verification

Verified by `GovernedAnalyticsTest` (15 scenarios on H2) plus a manual run of the same flow against a scratch SQL Server database (Flyway V1-V3, Hibernate schema validation, CSV import, metric approval and verification, grouped and monthly queries, chat).

| BRD ID | Status | Notes |
| --- | --- | --- |
| SRC-01 | Done | Create, test, pause/resume, rotate secret, per-workspace scoping; secrets AES-GCM encrypted, write-only |
| SRC-02 | Done | PostgreSQL and SQL Server via read-only sessions; only tables on `config.allowedTables` can be registered or queried |
| SRC-03 | Done | CSV preview (types, bad rows), size and row limits, REPLACE/APPEND, rejected-row report |
| SRC-04 | Done | Generic JSON REST connector: bearer/API-key auth, page or cursor pagination, mapping, incremental cursor, upsert by key, retries and 429 handling, schedule |
| SRC-05 | Done | "CSV dump v1" from an allowlisted https URL or manual upload: checksum pin, binary/malformed rejection, staging, imported once |
| SRC-06 | Done | Last success, next run, error, run history, stale flag; failed loads keep prior data and answers warn |
| SRC-10 | Done | Same service layer for UI and API; responses never include secrets |
| MOD-01 | Done | Catalogue with descriptions, owner, column roles, RESTRICTED sensitivity, freshness |
| MOD-02 | Done | Governed metrics (aggregation or simple column expression), dimensions, date column, synonyms, safe joins |
| MOD-03 | Done | Draft/approved, version history, diff, rollback |
| MOD-04 | Done | Baseline test and verify; join safety test (fan-out, orphans); unknown terms trigger clarification |
| ANA-01..03 | Done | Chat with follow-ups, clarification instead of guessing, answer + chart + SQL + definition + filters + freshness + warnings |
| ANA-04 | Done | Explore UI re-runs a permission-checked query on every change |
| ANA-05 | Done | Save, duplicate, share, CSV and PDF export (formula-injection safe); viewers re-run under their own access |
| GOV-01 | Done | Workspaces, DB-backed users, OWNER/ADMIN/ANALYST/VIEWER/EMBED roles, tenant-scoped queries |
| GOV-02 | Done | Row policies in the query service; restricted columns hidden and refused |
| GOV-03 | Done | Encrypted credentials, rotation, no secrets in responses or audit |
| GOV-04 | Done | Per-workspace daily query cap, row cap, timeout; outbound size/host limits |
| GOV-05 | Done | Audit of source, model, query, export, sharing, ingestion and denied access |

## Decisions to confirm (BRD section 10)

- Dump format chosen: UTF-8 CSV with header ("CSV dump v1"). REST connector chosen: generic JSON REST. Change if the sponsor names others.
- `APP_SECRET_KEY` (32-byte base64) must be set outside local development; `INGESTION_ALLOWED_HOSTS` lists hosts connectors may reach.

## Known limits

- Authentication is still HTTP Basic against the `app_users` table (SSO is P1). The frontend still signs in as the demo admin.
- The AI planner is deterministic (approved vocabulary only); no external LLM, token budget or caching yet.
- Not exercised against a live PostgreSQL server (tested through an H2 stand-in); SQL Server paths were exercised live.
- DNS-rebinding between URL validation and connect is not mitigated.
- Accessibility (WCAG), load tests, and backup/restore checks from BRD section 7/11 are not done.
- P1/P2 items (more connectors, dbt/LookML, artifacts, agents, embedding, SSO, MCP) are not started.
- Legacy Toscana I2P summary endpoints are unchanged but now require a signed-in member.

## Connecting the I2P database

Run `database\connect-i2p-source.ps1` against a running backend. It creates a SELECT-only login on `dbo.AccountsPayable_External`, registers it as a source, catalogues the table (user-name columns restricted) and creates approved metrics (invoice work items, average ageing, distinct invoices). Verified live: 215 work items, 143 India / 56 USA, monthly trend.
