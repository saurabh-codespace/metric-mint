import { apiRequest } from './client';

export type DashboardDefinition = {
  name: string;
  kind: 'executive' | 'workbook' | 'template';
  reportId?: string;
  days?: number;
  unit?: string;
  chart?: string;
};
export type Publication = DashboardDefinition & {
  id: string; token: string; publishedAt: string; ownerName: string; canRevoke: boolean;
};
export const publications = {
  list: () => apiRequest<Publication[]>('/published-dashboards'),
  publish: (definition: DashboardDefinition) => apiRequest<Publication>('/published-dashboards', { method: 'POST', body: JSON.stringify(definition) }),
  get: (token: string) => apiRequest<Publication>(`/published-dashboards/token/${encodeURIComponent(token)}`),
  revoke: (id: string) => apiRequest<void>(`/published-dashboards/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};
export const publicationUrl = (token: string) => `${window.location.origin}${window.location.pathname}#/published/${encodeURIComponent(token)}`;
