import { apiRequest, type ReportDatum } from './client';

export type WorkbookChart = 'bar' | 'column' | 'line' | 'area' | 'pie' | 'donut' | 'table';
export type WorkbookReport = {
  id: string; title: string; description: string; category: string; frequency: string;
  workbookRow: number; chartType: WorkbookChart; recordCount: number; generatedAt: string;
  sampleAsOf: string; data: ReportDatum[]; labelKey: string; valueKey: string;
  kpis: Array<{ label: string; value: number; unit: string }>;
};
export const workbooks = {
  list: () => apiRequest<WorkbookReport[]>('/workbook-reports'),
  get: (id: string) => apiRequest<WorkbookReport>(`/workbook-reports/${encodeURIComponent(id)}`),
};
