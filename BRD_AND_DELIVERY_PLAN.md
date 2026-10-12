# Business Requirements Document and Delivery Plan

**Working name:** Governed AI Analytics Platform  
**Version:** 1.0 planning draft  
**Research date:** 8 October 2026  
**Stack:** Spring Boot backend, React frontend  
**Status:** Business review only; no application implementation.

## 1. Purpose and reference

Build a cost-conscious analytics application where people connect business data, ask questions in ordinary language, inspect trustworthy answers, explore charts, and share reusable reports. Data teams approve definitions so revenue means the same thing throughout the application.

Metric Mint uses **Zenlytic**, the AI analytics product with its Zoë analyst, as an external feature reference. This document targets its **publicly documented features**, not its private implementation, brand, or a guaranteed pixel-for-pixel clone. Zenlytic's site and documentation describe governed conversational analytics, a semantic/context layer, verifiable answers, artifacts, proactive agents, embedding, and integrations. [S1–S8]

**Important difference:** Scheduled REST API ingestion and database dump retrieval/import are *requested features of our proposed application*. Public Zenlytic documentation confirms warehouse connections and CSV upload, but does not establish those two capabilities as general-purpose Zenlytic data-source features. [S2, S9]

## 2. Business need and outcomes

| Problem today | Desired outcome |
| --- | --- |
| A user waits for an analyst to answer an unplanned question. | The user gets a useful answer independently, with evidence. |
| Teams calculate the same metric differently. | Approved definitions are reused in every answer and report. |
| Data lives in databases, APIs, and exports. | Sources are governed, refreshed, and visibly marked when stale. |
| AI can invent numbers or generate dangerous queries. | Queries are permission-checked, read-only, bounded, and reproducible. |
| A full BI platform may be expensive to build and operate. | Ship high-value features first and cap variable costs. |

**Proposed pilot targets, not vendor claims:** Within 90 days of pilot launch, onboard two business teams and four source types; at least 60% of pilot users should answer a standard question without an analyst; at least 90% of a signed-off question set should be correct; monthly hosting/AI expenditure stays within an agreed cap. Measure actual usage, analyst time saved, and source-compute fees.

**People:** Workspace owner (policy/budget); data administrator (sources, users, permissions); analyst/data steward (metrics and review); business viewer (questions and reports); embedded customer (only their own permitted data); operator (system health, without default access to customer rows).

## 3. Scope and release priorities

- **P0 — first usable product:** Workspaces, PostgreSQL/CSV, one chosen API and dump format, refresh monitoring, governed metrics and joins, guarded chat-to-query, lineage, charts/exploration, saved reports, sharing, row/column access, budgets, and audit.
- **P1 — broad public-feature coverage:** More warehouse connectors, dbt/LookML context import, feedback/memory, rich artifacts and exports, scheduled agents, email/Slack/Teams, signed embedding, SSO, change review, and observability.
- **P2 — advanced coverage:** Self-learning model proposals, deterministic verification of approved versus new fields, MCP, voice/file-assisted chat, optional web search, advanced statistics/forecasting, and private deployment. [S1, S3–S8]
- **Not in P0:** Copying proprietary branding/code; arbitrary autonomous writes; all connectors; restoring untrusted dumps to production; claiming SOC 2/HIPAA certification without an audit; guaranteeing identical third-party AI responses.

## 4. Example journey

1. An admin creates a workspace and registers a read-only source, selecting allowed tables or a safe import profile.
2. The app checks connectivity, previews fields, and records the source owner, refresh status, and failures.
3. A steward defines "net revenue," checks its joins and known totals, and approves a version.
4. A user asks "How did net revenue change last quarter by region?" The app asks for clarification if needed, runs an authorized bounded query, and shows the answer, chart, source, filters, freshness, and query details.
5. The user drills down, asks a follow-up, and shares a report. A colleague only sees data permitted for *their* identity.
6. An analyst reviews feedback and improves the model. Later, an approved scheduled agent repeats and delivers the analysis.

## 5. Functional requirements

Priorities indicate release order. Acceptance checks describe what a working feature must prove.

### A. Sources and ingestion

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| SRC-01 | P0 | Name, test, pause, rotate, and monitor sources per workspace. | An admin can disable a connection; another workspace cannot see its credentials. |
| SRC-02 | P0 | Connect to PostgreSQL with a read-only account and selected schemas/tables. | Denied tables cannot be discovered or queried. |
| SRC-03 | P0 | Upload CSV with type/header preview, size limit, bad-row report, and replace/append choice. | Valid rows import with a visible count and timestamp; bad rows are reported. |
| SRC-04 | P0 | Implement **one named REST API connector** with approved auth, pagination, mapping, incremental cursor, retries, rate-limit handling, and schedule. | Initial and incremental runs produce expected data without duplicates. |
| SRC-05 | P0 | Fetch **one named structured dump format** on a schedule from approved object storage (or accept manual upload), then import into isolated staging; validate size, checksum, and format. | A new dump imports once; an unexpected file is rejected and no production database is overwritten. |
| SRC-06 | P0 | Show last success, source timestamp, next run, error, retry, and stale-data warning. | A failed refresh preserves prior good data and is visible in answers. |
| SRC-07 | P1 | Add Snowflake, BigQuery, Redshift, MySQL, Databricks SQL, Druid, MotherDuck/DuckDB, Trino, SQL Server, and Azure Synapse as demand warrants. | Each passes shared metadata, permission, timeout, and error tests. |
| SRC-08 | P1 | Allow images, PDF, CSV, and SQL guidance as *chat attachments*, separate from durable ingestion. | An attachment never silently becomes a shared dataset. |
| SRC-09 | P1 | Detect schema drift and review changed columns before republishing models. | Affected reports and metrics are identified. |
| SRC-10 | P0 | Configure/test/disable sources through either the admin UI or an authenticated, scoped management API. | The same validation and audit rules apply to both paths; API responses never return stored secrets. |

Zenlytic documents the SRC-07 warehouses, CSV table upload, and chat attachments. SRC-04/05 are explicit requested extensions. [S2, S9, S10]

### B. Business definitions and trust

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| MOD-01 | P0 | Catalog source tables, fields, descriptions, owners, sensitivity, and freshness. | A user finds approved fields by business name; restricted fields remain hidden. |
| MOD-02 | P0 | Define reusable dimensions, measures, dates/time zones, filters, joins, and synonyms in a semantic layer. | Approved "net revenue" produces the same result in chat and reports. |
| MOD-03 | P0 | Mark definitions draft/approved with author, approver, version, and rollback. | A published change can be compared and reverted. |
| MOD-04 | P0 | Test joins and aggregations against known totals; warn on duplication or unknown terms. | An unsafe join is not silently labeled verified. |
| MOD-05 | P1 | Import dbt/MetricFlow or LookML definitions with a mapping preview and manual review. | Unsupported constructs are reported; a sample metric matches its baseline. |
| MOD-06 | P1 | Let analysts create personal calculated fields, then request promotion to shared definitions. | Unapproved fields are visibly labeled; promotion needs a steward. |
| MOD-07 | P1 | Save approved examples, glossary, user feedback, and business rules with expiry/removal controls. | An admin can audit and remove incorrect guidance. |
| MOD-08 | P2 | Suggest definitions from schema, query history, or uploaded context, with a reviewable diff. | No agent-generated change reaches the shared model without authorization. |

The reference models connections, views, dimensions, measures, and joins, and describes memories, dynamic fields, and governed self-learning. [S3, S6, S11]

### C. Ask, explore, and present

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| ANA-01 | P0 | Ask plain-language questions, refine with follow-ups, and revisit chat history. | A user changes a time period in the same conversation. |
| ANA-02 | P0 | Create authorized, read-only, bounded queries; ask for clarification rather than guessing. | Unclear intent triggers a question; forbidden SQL never executes. |
| ANA-03 | P0 | Show answer, table/chart, source tables, metric definitions, filters, SQL, freshness, and warnings. | A reviewer can reproduce a number from its lineage. |
| ANA-04 | P0 | Change chart type, metrics, dimensions, filters, sorting, dates, and row limits without SQL. | Every edit reruns a permission-checked query. |
| ANA-05 | P0 | Save, duplicate, organize, share, and export simple reports (CSV/PDF initially). | A recipient cannot bypass source access through a link/export. |
| ANA-06 | P1 | Produce versioned artifacts: narratives, live charts, interactive reports, spreadsheets, presentations, and documents. | A live view refreshes under the viewer's permissions; static files show generation time. |
| ANA-07 | P1 | Offer correct, retry, explain, and flag-for-review actions. | Feedback reaches an analyst queue, not the live model automatically. |
| ANA-08 | P2 | Support bounded comparisons, cohorts, forecasting/statistics, voice, attachments, and cited web research. | Advanced outputs state assumptions and external-data use. |

Zenlytic documents natural-language analysis, inspectable query/chart details, citations, exploration, streaming, attachments, and live artifacts. Its newer documentation calls older dashboards legacy and favors artifacts. [S1, S4, S12]

### D. Automation, sharing, and integrations

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| AUT-01 | P1 | Save a conversation as a reusable multi-step agent with inputs and test runs. | Another authorized user can supply inputs and repeat the steps. |
| AUT-02 | P1 | Schedule runs and report refreshes; deliver by email, then Slack/Teams; retain run history and retries. | A failed run is visible and no recipient receives unauthorized data. |
| AUT-03 | P1 | Share reports/agents with groups as viewer, editor, or owner; revoke access. | Revocation applies to subsequent views and runs. |
| AUT-04 | P1 | Embed chat/reports privately or via short-lived signed access in another app. | Embedded users see only their tenant's permitted rows/columns. |
| AUT-05 | P1 | Expand the P0 source-management API to governed query/report retrieval and signed embed-token issuance. | Calls are rate-limited, permission-checked, and audited. |
| AUT-06 | P2 | Provide an MCP server for governed questions and allowlisted MCP client for external tools. | Tool calls are logged; outward actions require approval. |

The reference documents proactive agents, scheduling, sharing, signed/private embedding, Slack/Teams, and both directions of MCP. The proposed management/ingestion API is not a claim of identical vendor endpoints. [S4, S5, S7, S8]

### E. Security, administration, and operations

| ID | Priority | Requirement | Acceptance check |
| --- | --- | --- | --- |
| GOV-01 | P0 | Separate workspaces and roles: owner, admin, analyst, viewer, embed. | Cross-tenant UI, API, export, job, and cache tests fail safely. |
| GOV-02 | P0 | Enforce row/column policies in the query service, not just React. | Different users receive appropriately different results. |
| GOV-03 | P0 | Encrypt and rotate credentials; use least-privilege accounts and redact logs. | No API response or log reveals a source secret. |
| GOV-04 | P0 | Limit rows, bytes, duration, concurrency, AI tokens, and per-workspace spending. | Oversized jobs stop before incurring unapproved cost. |
| GOV-05 | P0 | Audit source changes, model approvals, queries, exports, sharing, and denied access. | An admin can investigate without unrestricted raw-data access. |
| GOV-06 | P1 | Add SSO (OIDC/SAML as needed), groups, and agent-level permissions. | Revoking a user/group removes access on the next request. |
| GOV-07 | P1 | Show questions, failures, adoption, definition gaps, ingestion health, latency, and spend. | Owner identifies top unanswered questions and costly sources. |
| GOV-08 | P2 | Offer private-network deployment and formal compliance readiness where needed. | No certification is advertised before independently earned. |

The reference advertises role-based security/SSO and documents row/column controls. Certification for *our* product needs a separate audit. [S1, S13]

## 6. Business rules

1. Customer operational sources are **read-only**. Imports write only to isolated staging/analytics storage.
2. Only an authorized steward may label a tested definition **verified**. Other results are labeled exploratory.
3. Permissions apply during discovery, AI context selection, query compilation, execution, caching, export, scheduled delivery, and embedding.
4. Live shared reports use each viewer's access, not the author's. Scheduled emails have an explicit approved delivery identity/policy.
5. Stale, unavailable, or contradictory data yields a warning or refusal, never an invented number.
6. Sync/import jobs are idempotent, resumable, and keep prior good data until validation succeeds.
7. Dumps are quarantined, scanned, format-checked, and never executed as arbitrary scripts or restored over production. Remote URLs are allowlisted and cannot reach internal services.
8. Uploads and external content are treated as data, not instructions to the AI or application.

## 7. Non-functional requirements (pilot targets)

These are proposed acceptance targets; finalize them after sizing real data and budget.

| Area | Target |
| --- | --- |
| Availability | 99.5% monthly for the pilot app excluding agreed maintenance; show source outages separately. |
| Speed | Cached simple report under 3 seconds at p95; fresh simple query under 15 seconds at p95, excluding source outages/large jobs. |
| Freshness | Direct queries show source timestamp; imports show last complete sync and warn above a configured age. |
| Capacity | Pilot: 2 workspaces, 100 named users, 20 concurrent questions, 10 scheduled jobs; load-test before increasing. |
| Recovery | Daily metadata backup; proposed RPO 24 hours and RTO 8 hours for pilot. Customer source data remains in their systems. |
| Accessibility | Keyboard navigation, text equivalents for charts; target WCAG 2.2 AA for production. |
| Privacy | Configurable retention/deletion of chat, uploads, logs, and staged data; minimize data sent to AI providers. |
| Quality | Golden-question regression suite, refusal/clarification tests, and human review of high-impact financial decisions. |
| Cost | Hard workspace AI budget, source-compute warnings, and breakdown by tenant/source/feature. |

## 8. Cost-conscious solution approach

**Logical flow:** React + TypeScript UI → Spring Boot modular monolith (identity/workspaces, sources, models, query service, chat/reports, jobs, audit) → customer databases/warehouses for read-only push-down queries. PostgreSQL stores metadata, model versions, job state, and modest pilot imports. Object storage holds files/exports. A bounded background worker handles imports, schedules, and long analyses. The AI provider sees only the minimum permitted schema/context and suggests a plan; the backend validates and executes it.

**Why this starts cheaply:** One backend and metadata database are simpler than many microservices. Query customer warehouses instead of copying every table. Use a small/fast model for routine intent and a stronger one only for complex analysis; compile approved metric queries deterministically where possible; cache results by user/permission/model version/freshness; impose budgets and limits. Add enterprise connectors and delivery channels when demanded. Avoid an always-on vector database, Kubernetes cluster, or arbitrary-code sandbox in P0.

**Monthly cost formula:** fixed hosting + database/object storage + AI calls × average token price + warehouse query/egress charges + delivery charges. Track actual pilot calls, scanned bytes, storage, and job schedules; obtain current provider quotes before promising a price. Customer warehouse compute may be billed separately. Define a monthly cap and alert thresholds during discovery.

**Architecture boundaries:** Connector adapters and SQL dialects feed a central permission-aware query service. Browser and AI never receive source credentials or execute arbitrary SQL. A future Python/HTML artifact runner needs isolated compute, network restrictions, resource limits, and security review. Cross-warehouse live joins are not promised in P0; explicitly stage a dataset or validate a separate federation solution.

## 9. Delivery plan

Indicative timing assumes one well-defined pilot domain and a dedicated small team. Gates need business sign-off.

| Phase | Time | Work | Exit gate |
| --- | --- | --- | --- |
| 0. Discover | 2–3 weeks | Interview users; choose two teams, API, dump format, warehouse, known metrics; inspect volumes and security; write 30–50 golden questions. | Approved scope, sample data, metric owners, and budget cap. |
| 1. Foundation | 6–8 weeks | React shell, Spring Boot modules, roles, PostgreSQL/CSV, chosen API and dump importer, source-management API, source health, CI/backups. | Safe source onboarding and tenant/permission tests. |
| 2. Trusted-answer MVP | 6–8 weeks | Semantic definitions, guarded queries, chat, lineage, charts/Explore, basic reports, issue flagging, usage metering. | Pilot questions pass and no critical access leak remains. |
| 3. Pilot hardening | 3–4 weeks | Load/security/accessibility tests, retries, monitoring, user training, support, cost tuning. | Pilot targets met or exceptions accepted explicitly. |
| 4. Broad product | 8–12 weeks | Demand-led warehouses, model change workflow, context imports, artifacts, agents, schedules, embedding, SSO, integrations. | Each addition passes permission, reliability, and cost tests. |
| 5. Advanced parity | Separately estimated | Self-learning, deterministic field verification, MCP, voice/web, advanced analysis, private deployment. | Security review and demand justify complexity. |

**Pilot estimate:** About **17–23 weeks** through phase 3, dependent on access and data quality. P1 adds approximately **8–12 weeks**, not an unlimited set of bespoke connectors. Suggested team: part-time product owner/BA and designer; two backend/data engineers; one frontend engineer; shared QA, security, and DevOps. Size staffing and money after phase 0, not from an unsupported vendor-price assumption.

**Dependencies:** Read-only source accounts and sample data; API contract/rate limits; approved dump specification; metric owners and correct example answers; identity-provider access if SSO is needed; hosting/AI policies; legal/privacy review of transfers and retention.

## 10. Risks and open decisions

| Risk | Treatment / decision |
| --- | --- |
| Exact third-party feature parity is not observable from public material. | Maintain a comparison list; validate gaps via a licensed trial/demo if needed. |
| Wrong totals due to joins or inconsistent definitions. | Steward approval, baseline tests, provenance, exploratory labels, and regression questions. |
| AI or warehouse bills grow unexpectedly. | Caps, scoped caching, approvals for large jobs, and per-tenant spending reports. |
| API schema/auth/rate limits change. | Connector contracts, health checks, incremental checkpoints, and retry alerts. |
| Dumps include executable SQL, malware, secrets, or personal data. | Prefer structured exports; quarantine, scan, isolate, and enforce retention. |
| Sharing, caches, prompts, exports, or schedules leak data. | Central authorization, least privilege, tenant-isolation tests, security review. |
| Cross-source analysis is costly or slow. | Begin with single-source queries and explicit imports; assess federation separately. |

**Sponsor decisions:** Confirm the reference is Zenlytic; name the first API and dump format; select pilot domain and metric owners; choose cloud/on-prem and residency policy; approve monthly AI/hosting and warehouse-compute caps; decide whether SSO or embedding is needed at launch. These decisions change schedule and price.

## 11. Definition of done and handover

The MVP is accepted when admins can safely configure every chosen P0 source; analysts can approve and roll back tested metrics; viewers can ask, inspect, filter, save, and share; restricted users cannot reach hidden data through any surface; a failed import preserves good data; source freshness and spend are visible; golden-question, isolation, backup/restore, and accessibility checks pass; and pilot owners accept the measured results or record exceptions.

Handover includes source/metric catalogues, user/admin guides, failed-sync and bad-answer runbooks, security/retention settings, usage dashboard, open issues, and a P1 backlog ranked by measured demand.

## 12. Simple glossary

- **Data source:** A database, warehouse, API, or file containing information to analyze.
- **Semantic layer:** Shared dictionary of business calculations, joins, names, and rules.
- **Metric/measure:** Calculation such as sales. **Dimension:** Grouping such as month or region.
- **Lineage:** Where a displayed value came from and how it was calculated.
- **Artifact:** A saved, possibly live report, chart, spreadsheet, or presentation.
- **Proactive agent:** Reusable steps that can run again or on a schedule.
- **Workspace/tenant:** One organization's isolated users, settings, and access.
- **RPO/RTO:** Maximum acceptable data loss after an incident / target restoration time.

## 13. Research references

Public primary sources checked on 8 October 2026. They establish documented **reference-product** capabilities, not our product's future certification or guaranteed equivalence.

- **S1:** [Zenlytic product site](https://zenlytic.com/) — product, context, verification, integrations, security claims.
- **S2:** [Warehouse integrations](https://docs.zenlytic.com/data-sources/integrations) — supported source list.
- **S3:** [Data-modeling overview](https://docs.zenlytic.com/data-modeling/data_modeling) — models, views, fields, relationships.
- **S4:** [Using Zoë](https://docs.zenlytic.com/using-zenlytic/zoe) — chat, query inspection, citations, artifacts, Slack/Teams.
- **S5:** [Proactive Agents](https://docs.zenlytic.com/proactive-agents/getting-started) — reusable conversations and runs.
- **S6:** [Self-learning announcement](https://zenlytic.com/blog/announcing-self-learning) — governed model updates.
- **S7:** [Embedding overview](https://docs.zenlytic.com/embedding/embedding_overview) — private/signed access.
- **S8:** [MCP overview](https://docs.zenlytic.com/mcp/overview) — inbound/outbound connections.
- **S9:** [Adding a table](https://docs.zenlytic.com/data-modeling/adding_a_new_table) — warehouse/CSV upload.
- **S10:** [File uploads in chat](https://support.zenlytic.com/en/articles/13574025-uploading-files-to-a-chat-or-proactive-analytics-agent) — attachment examples.
- **S11:** [Steering answers](https://docs.zenlytic.com/tips-and-tricks/zoe_context_ingestion) — context and memories.
- **S12:** [Explore](https://docs.zenlytic.com/zenlytic-ui/exploring) — metrics, filters, dates, calculations.
- **S13:** [Access grants](https://docs.zenlytic.com/data-modeling/access_grants) — row/column controls.
