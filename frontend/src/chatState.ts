import type { ChatAnswer, SavedQuestion } from './api/chat';

export type ChatMessage = { id: string; role: 'user' | 'assistant'; text: string; answer?: ChatAnswer; error?: string; status: 'pending' | 'complete' | 'stopped' | 'error' };
export type ChatThread = { id: string; title: string; messages: ChatMessage[]; createdAt: string; projectId?: string | null; questionCount?: number; loaded?: boolean; loading?: boolean; loadError?: string; deleting?: boolean; unsentDraft?: string; unsentAfter?: number };

export function questionMessages(question: SavedQuestion): ChatMessage[] {
  const answer = { ...question, query: question.spec, rows: question.rows ?? [], warnings: question.warnings ?? [], insights: question.insights ?? [], explanation: question.explanation ?? '' };
  return [
    { id: `${question.id}:user`, role: 'user', text: question.question, status: 'complete' },
    { id: question.id, role: 'assistant', text: question.answer ?? '', status: question.status ?? 'complete', answer: question.status === 'pending' || question.status === 'error' ? undefined : answer, error: question.error ?? undefined },
  ];
}
