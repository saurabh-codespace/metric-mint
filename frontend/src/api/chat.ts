import { apiRequest, type ReportDatum } from './client';
import type { Lineage, QuerySpec } from './governed';

export type ChatAnswer = {
  answer: string; visualization: string; rows: ReportDatum[]; explanation: string;
  clarification?: string | null; label?: string | null; warnings: string[];
  lineage?: Lineage | null; insights: string[]; aiGenerated: boolean;
  query?: QuerySpec | null; executedAt?: string | null;
};
export type ChatProject = { id: string; name: string; createdAt: string };
export type Conversation = { id: string; title: string; createdAt: string; questionCount: number; projectId?: string | null };
export type SavedQuestion = Omit<ChatAnswer, 'query'> & {
  id: string; conversationId: string; question: string; spec?: QuerySpec | null; createdAt: string;
  status: 'pending' | 'complete' | 'error'; error?: string | null;
};
const conversationPath = (id: string) => `/conversations/${encodeURIComponent(id)}`;
export const chatApi = {
  projects: (signal: AbortSignal) => apiRequest<ChatProject[]>('/projects', { signal }),
  createProject: (name: string, signal: AbortSignal) => apiRequest<ChatProject>('/projects', { method: 'POST', body: JSON.stringify({ name }), signal }),
  conversations: (signal: AbortSignal) => apiRequest<Conversation[]>('/conversations', { signal }),
  create: (title: string, projectId: string | null, signal: AbortSignal) => apiRequest<Conversation>('/conversations', { method: 'POST', body: JSON.stringify({ title, projectId }), signal }),
  questions: (id: string, signal: AbortSignal) => apiRequest<SavedQuestion[]>(`${conversationPath(id)}/questions`, { signal }),
  ask: (id: string, question: string, signal: AbortSignal) => apiRequest<SavedQuestion>(`${conversationPath(id)}/questions`, { method: 'POST', body: JSON.stringify({ question }), signal }),
  remove: (id: string, signal: AbortSignal) => apiRequest<void>(conversationPath(id), { method: 'DELETE', signal }),
};
