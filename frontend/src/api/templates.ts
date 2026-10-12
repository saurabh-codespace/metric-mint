import { apiRequest, type ReportDatum } from './client';
import type { WorkbookChart } from './workbooks';

export type TemplateWidget = { id: string; title: string; sourceType: 'workbook' | 'report' | 'connected'; sourceId: string; chart: WorkbookChart; labelKey: string; valueKey: string; columns: string[]; width: 'half' | 'full' };
export type TemplateDefinition = { version: 1; name: string; description: string; widgets: TemplateWidget[] };
export type SavedTemplate = { id: string; definition: TemplateDefinition; ownerName: string; canEdit: boolean; createdAt: string; updatedAt: string };
export type TemplateSource = { type: TemplateWidget['sourceType']; id: string; name: string; sample: boolean };
export type SourceData = { columns: string[]; rows: ReportDatum[]; sample: boolean; warnings: string[]; executedAt: string };
export type RunResult = { definition: TemplateDefinition; widgets: (SourceData & { widget: TemplateWidget })[] };
export type GeneratedDashboard = { result?: RunResult | null; aiGenerated: boolean; message: string; clarification?: string | null };
const path = (id: string) => `/report-templates/${encodeURIComponent(id)}`;
export const templates = {
  aiStatus: (signal: AbortSignal) => apiRequest<{ configured: boolean }>('/report-templates/ai/status', { signal }),
  generate: (prompt: string, dataMode: 'live' | 'sample', signal: AbortSignal) => apiRequest<GeneratedDashboard>('/report-templates/generate', { method: 'POST', body: JSON.stringify({ prompt, dataMode }), signal }),
  list: () => apiRequest<SavedTemplate[]>('/report-templates'),
  get: (id: string) => apiRequest<SavedTemplate>(path(id)),
  create: (definition: TemplateDefinition) => apiRequest<SavedTemplate>('/report-templates', { method: 'POST', body: JSON.stringify(definition) }),
  update: (id: string, definition: TemplateDefinition) => apiRequest<SavedTemplate>(path(id), { method: 'PUT', body: JSON.stringify(definition) }),
  remove: (id: string) => apiRequest<void>(path(id), { method: 'DELETE' }),
  preview: (definition: unknown) => apiRequest<RunResult>('/report-templates/preview', { method: 'POST', body: JSON.stringify(definition) }),
  run: (id: string) => apiRequest<RunResult>(`${path(id)}/run`),
  sources: () => apiRequest<TemplateSource[]>('/report-templates/sources'),
  source: (type: TemplateWidget['sourceType'], id: string) => apiRequest<SourceData>(`/report-templates/sources/${type}/${encodeURIComponent(id)}`),
  published: (token: string) => apiRequest<RunResult>(`/published-dashboards/token/${encodeURIComponent(token)}/data`),
};
