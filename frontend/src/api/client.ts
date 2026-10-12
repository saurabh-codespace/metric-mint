import type { Invoice } from '../dashboardData';

export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api';

export type ToscanaOverview = {
  databaseName: string;
  lastRefreshed: string;
  totalWorkItems: number;
  completedWorkItems: number;
  openWorkItems: number;
  activeLobs: number;
  completionRate: number;
  lobs: Array<{ name: string; total: number; completed: number; open: number; avgAgeing: number }>;
  statuses: Array<{ name: string; count: number }>;
  monthlyVolume: Array<{ period: string; count: number }>;
  queues: Array<{ name: string; count: number }>;
};

export type SchemaTable = { schema: string; name: string; rowCount: number; category: string };
export type ReportDatum = Record<string, string | number | null>;
export type ChartType = 'bar' | 'column' | 'pie' | 'donut' | 'line' | 'area' | 'stacked-bar' | 'radar' | 'heatmap' | 'scatter' | 'table' | 'kpi';
export type ToscanaReport = {
  id: string;
  title: string;
  description: string;
  category: string;
  chartType: ChartType;
  status: string;
  sourceType: string;
  recordCount: number;
  generatedAt: string;
  data: ReportDatum[];
};

export type DataSourceType = string | { type?: string; value?: string; label?: string; name?: string };

export type DataSource = {
  id: string;
  name: string;
  type: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  credentialReference: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type CreateDataSource = Pick<DataSource, 'name' | 'type' | 'host' | 'port' | 'databaseName' | 'username' | 'credentialReference'>;
export type AuthUser = { username: string; displayName: string; roles: string[]; workspaceId: string };

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

const AUTH_KEY = 'toscana.auth';
type Credentials = { username: string; password: string };
const storedCredentials = (): Credentials | null => {
  try { return JSON.parse(sessionStorage.getItem(AUTH_KEY) ?? 'null') as Credentials | null; }
  catch { return null; }
};
export const hasCredentials = () => storedCredentials() !== null;
export const clearCredentials = () => sessionStorage.removeItem(AUTH_KEY);
export const authorization = (credentials = storedCredentials()) => {
  if (!credentials) return '';
  return `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`;
};

async function authenticate(username: string, password: string) {
  const credentials = { username, password };
  const response = await fetch(`${API_BASE}/auth/me`, { headers: { Authorization: authorization(credentials) } });
  if (!response.ok) throw new ApiError(response.status, response.status === 401 ? 'Incorrect username or password.' : 'Sign in is unavailable.');
  const user = await response.json() as AuthUser;
  sessionStorage.setItem(AUTH_KEY, JSON.stringify(credentials));
  return user;
}

export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(authorization() ? { Authorization: authorization() } : {}),
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const message = await response.text();
    if (response.status === 401) {
      clearCredentials();
      window.dispatchEvent(new Event('toscana:unauthorized'));
    }
    throw new ApiError(response.status, message || `Request failed (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  getDashboardInvoices: () => apiRequest<Invoice[]>('/dashboard/sample-invoices'),
  login: authenticate,
  me: () => apiRequest<AuthUser>('/auth/me'),
  ask: (question: string, mode: 'answer' | 'dashboard' = 'answer') => apiRequest('/ai/ask', { method: 'POST', body: JSON.stringify({ question, mode }) }),
  getOverview: () => apiRequest<ToscanaOverview>('/toscana/overview'),
  getSchema: () => apiRequest<SchemaTable[]>('/toscana/schema'),
  getToscanaReports: () => apiRequest<ToscanaReport[]>('/toscana/reports'),
  refreshToscanaCache: () => apiRequest<void>('/toscana/cache/refresh', { method: 'POST' }),
  getSources: () => apiRequest<DataSource[]>('/data-sources'),
  getSourceTypes: () => apiRequest<DataSourceType[]>('/data-sources/types'),
  createSource: (source: CreateDataSource) => apiRequest<DataSource>('/data-sources', { method: 'POST', body: JSON.stringify(source) }),
};
