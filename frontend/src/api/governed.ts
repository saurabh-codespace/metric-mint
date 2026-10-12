import { API_BASE, ApiError, apiRequest, authorization } from './client';

export type Lineage = { sql: string; parameters: string[]; source: string; tables: string[]; metricDefinition: string; metricVersion: number; metricStatus: string; metricVerified: boolean; dataAsOf: string; filters: string[] };
export type QuerySpec = {
  metric: string; dimensions: string[]; filters: Array<{ column: string; op: string; values: string[] }>;
  timeGrain?: string | null; dateRange?: { from: string | null; to: string | null } | null; sortBy?: string | null; sortDir?: string | null; limit?: number | null;
};
export type QueryResult = { metric: string; metricName: string; columns: string[]; rows: Array<Record<string, string | number | null>>; truncated: boolean; label: 'VERIFIED' | 'EXPLORATORY'; warnings: string[]; lineage: Lineage; executedAt: string; spec: QuerySpec };
export type Dimension = { ref: string; label: string; type: string };
export type MetricOption = { key: string; name: string; description: string; status: string; verified: boolean; dimensions: Dimension[]; hasTime: boolean };

export type Metric = {
  id: string; name: string; keyName: string; description: string; aggregation: string; sourceField: string; active: boolean; tableId: string | null; dateColumn: string | null;
  synonyms: string[]; baselineValue: number | null; status: 'DRAFT' | 'APPROVED'; version: number; author: string; approvedBy: string | null; verified: boolean; verifiedBy: string | null;
};
export type MetricVersion = { version: number; status: string; changeType: string; changedBy: string; at: string };
export type MetricRequest = Pick<Metric, 'name' | 'keyName' | 'description' | 'aggregation' | 'sourceField' | 'active' | 'tableId' | 'dateColumn' | 'baselineValue'> & { synonyms: string[] };

export type CatalogColumn = { name: string; type: string; label: string; description: string; role: string; sensitivity: string; synonyms: string[] };
export type CatalogTable = { id: string; sourceId: string; name: string; description: string; owner: string; rowCount: number; lastLoadedAt: string | null; stale: boolean; columns: CatalogColumn[] };
export type Relationship = { id: string; fromTableId: string; fromColumn: string; toTableId: string; toColumn: string; cardinality: string; testStatus: string; testMessage: string | null };
export type Policy = { id: string; tableId: string; columnName: string; matchValue: string; role: string | null; username: string | null };

export type RunView = { id: string; status: string; trigger: string; rowsLoaded: number; rowsRejected: number; message: string | null; tableName: string | null; startedAt: string; finishedAt: string | null; badRows: Array<{ row: number; reason: string }> };
export type CsvPreview = { columns: Array<{ name: string; kind: string }>; sample: Array<Record<string, string>>; validRows: number; badRowCount: number; badRows: Array<{ row: number; reason: string }> };
export type SourceHealth = { id: string; hasSecret: boolean; scheduleMinutes: number | null; nextRunAt: string | null; lastSuccessAt: string | null; lastRunStatus: string | null; lastError: string | null; lastTestMessage: string | null; stale: boolean };

export type SavedReport = { id: string; name: string; visualization: string; query: QuerySpec | null; favorite: boolean; shared: boolean; owner: string; mine: boolean };
export type WorkspaceView = { id: string; name: string; maxQueriesPerDay: number; maxRows: number; queryTimeoutSeconds: number; staleAfterMinutes: number; queriesToday: number };
export type UserView = { id: string; username: string; displayName: string; role: string; enabled: boolean };
export type AuditEvent = { id: string; actor: string; action: string; resourceType: string; resourceId: string | null; details: string | null; createdAt: string };
export type AskAnswer = { answer: string; interpretation: string | null; clarification: string | null; result: QueryResult | null };

const post = <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
const put = <T>(path: string, body: unknown) => apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) });

export async function download(path: string, filename: string) {
  const response = await fetch(`${API_BASE}${path}`, { headers: { Authorization: authorization() } });
  if (!response.ok) throw new ApiError(response.status, await response.text());
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url; link.download = filename; link.click();
  URL.revokeObjectURL(url);
}

export const governed = {
  options: () => apiRequest<MetricOption[]>('/query/options'),
  runQuery: (spec: QuerySpec) => post<QueryResult>('/query', spec),
  ask: (question: string, previous?: QuerySpec) => post<AskAnswer>('/query/ask', { question, previous }),

  metrics: () => apiRequest<Metric[]>('/metrics'),
  createMetric: (m: MetricRequest) => post<Metric>('/metrics', m),
  updateMetric: (id: string, m: MetricRequest) => put<Metric>(`/metrics/${id}`, m),
  approveMetric: (id: string) => post<Metric>(`/metrics/${id}/approve`),
  verifyMetric: (id: string) => post<{ passed: boolean; message: string }>(`/metrics/${id}/verify`),
  testMetric: (id: string) => post<{ passed: boolean; message: string }>(`/metrics/${id}/test`),
  metricVersions: (id: string) => apiRequest<MetricVersion[]>(`/metrics/${id}/versions`),
  rollbackMetric: (id: string, version: number) => post<Metric>(`/metrics/${id}/rollback?version=${version}`),

  tables: () => apiRequest<CatalogTable[]>('/catalog/tables'),
  updateTable: (id: string, body: { description?: string; owner?: string; columns?: Array<Partial<CatalogColumn> & { name: string }> }) => put<CatalogTable>(`/catalog/tables/${id}`, body),
  relationships: () => apiRequest<Relationship[]>('/catalog/relationships'),
  testRelationship: (id: string) => post<Relationship>(`/catalog/relationships/${id}/test`),
  policies: () => apiRequest<Policy[]>('/catalog/policies'),
  createPolicy: (p: { tableId: string; columnName: string; matchValue: string; role?: string; username?: string }) => post<Policy>('/catalog/policies', p),
  deletePolicy: (id: string) => apiRequest<void>(`/catalog/policies/${id}`, { method: 'DELETE' }),

  testSource: (id: string) => post<{ ok: boolean; message: string }>(`/data-sources/${id}/test`),
  pauseSource: (id: string) => post(`/data-sources/${id}/pause`),
  resumeSource: (id: string) => post(`/data-sources/${id}/resume`),
  runSource: (id: string) => post<RunView>(`/data-sources/${id}/run`),
  sourceRuns: (id: string) => apiRequest<RunView[]>(`/data-sources/${id}/runs`),
  sourceHealth: () => apiRequest<SourceHealth[]>('/data-sources'),
  previewCsv: (file: File) => { const f = new FormData(); f.append('file', file); return apiRequest<CsvPreview>('/data-sources/csv/preview', { method: 'POST', body: f }); },
  uploadCsv: (id: string, file: File, table: string, mode: 'REPLACE' | 'APPEND') => {
    const f = new FormData(); f.append('file', file); f.append('table', table); f.append('mode', mode);
    return apiRequest<RunView>(`/data-sources/${id}/upload`, { method: 'POST', body: f });
  },

  reports: () => apiRequest<SavedReport[]>('/reports'),
  saveReport: (r: { name: string; visualization: string; query: QuerySpec; shared: boolean; favorite?: boolean }) => post<SavedReport>('/reports', { favorite: false, ...r }),
  runReport: (id: string) => apiRequest<QueryResult>(`/reports/${id}/run`),
  deleteReport: (id: string) => apiRequest<void>(`/reports/${id}`, { method: 'DELETE' }),

  workspace: () => apiRequest<WorkspaceView>('/workspace'),
  setLimits: (l: Omit<WorkspaceView, 'id' | 'name' | 'queriesToday'>) => put<WorkspaceView>('/workspace/limits', l),
  users: () => apiRequest<UserView[]>('/users'),
  createUser: (u: { username: string; displayName: string; password: string; role: string }) => post<UserView>('/users', u),
  audit: () => apiRequest<AuditEvent[]>('/audit-events?limit=50'),
};
