import { useEffect, useRef, useState } from 'react';
import { chatApi, type ChatProject } from './api/chat';
import { ApiError } from './api/client';
import { questionMessages, type ChatMessage, type ChatThread } from './chatState';

type State = { identity: string | undefined; threads: ChatThread[]; projects: ChatProject[]; activeId: string | null; projectId: string | null; loading: boolean; hydrated: boolean; error: string; creating: boolean; projectSaving: boolean; projectError: string };
const initial = (identity?: string): State => ({ identity, threads: [], projects: [], activeId: null, projectId: null, loading: !!identity, hydrated: false, error: '', creating: false, projectSaving: false, projectError: '' });
const selectionKey = (identity: string) => `metric-mint.chat-selection:${encodeURIComponent(identity)}`;
export function useChatThreads(identity: string | undefined) {
  const [state, setState] = useState<State>(() => initial(identity));
  const stateRef = useRef(state); stateRef.current = state;
  const identityRef = useRef(identity); identityRef.current = identity;
  const session = useRef({ identity });
  const focus = useRef(0);
  const active = useRef<string | null>(null);
  const project = useRef<string | null>(null);
  const creating = useRef(false);
  const projectSaving = useRef(false);
  const controllers = useRef(new Set<AbortController>());
  const requests = useRef(new Map<string, { controller: AbortController }>());
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const newController = () => { const controller = new AbortController(); controllers.current.add(controller); return controller; };
  const valid = (token: typeof session.current) => session.current === token && identityRef.current === token.identity && !!token.identity;
  const update = (token: typeof session.current, fn: (previous: State) => State) => {
    if (valid(token)) setState(previous => previous.identity === token.identity && valid(token) ? fn(previous) : previous);
  };
  const updateThread = (token: typeof session.current, id: string, fn: (thread: ChatThread) => ChatThread) => update(token, previous => ({ ...previous, threads: previous.threads.map(thread => thread.id === id ? fn(thread) : thread) }));
  const clearTimer = (id: string) => { clearTimeout(timers.current.get(id)); timers.current.delete(id); };
  const cancelThread = (id: string) => { clearTimer(id); requests.current.get(id)?.controller.abort(); requests.current.delete(id); };

  const reloadHistory = async (restore = false) => {
    const token = session.current; if (!valid(token)) return;
    const controller = newController(); update(token, previous => ({ ...previous, loading: true, error: '' }));
    try {
      const [projects, conversations] = await Promise.all([chatApi.projects(controller.signal), chatApi.conversations(controller.signal)]);
      if (!controller.signal.aborted && valid(token)) {
        let restoredId: string | null = null; let restoredProject: string | null = null;
        if (restore && token.identity) {
          try {
            const saved = JSON.parse(localStorage.getItem(selectionKey(token.identity)) ?? 'null');
            const conversation = conversations.find(item => item.id === saved?.conversationId);
            restoredId = conversation?.id ?? null;
            const projectId = conversation ? conversation.projectId : saved?.projectId;
            restoredProject = projects.some(item => item.id === projectId) ? projectId : null;
          } catch { /* An unavailable preference store does not prevent loading server history. */ }
          active.current = restoredId; project.current = restoredProject;
        }
        update(token, previous => ({ ...previous, projects, loading: false, hydrated: true, activeId: restore ? restoredId : previous.activeId, projectId: restore ? restoredProject : previous.projectId, threads: conversations.map(conversation => ({ ...previous.threads.find(thread => thread.id === conversation.id), ...conversation, messages: previous.threads.find(thread => thread.id === conversation.id)?.messages ?? [] })) }));
        if (restoredId) void loadThread(restoredId);
      }
    } catch { if (!controller.signal.aborted) update(token, previous => ({ ...previous, loading: false, error: 'Could not load your saved chats and projects. Please retry.' })); }
    finally { controllers.current.delete(controller); }
  };
  useEffect(() => {
    for (const controller of controllers.current) controller.abort(); controllers.current.clear(); requests.current.clear();
    for (const timer of timers.current.values()) clearTimeout(timer); timers.current.clear();
    session.current = { identity }; active.current = null; project.current = null; creating.current = false; projectSaving.current = false; focus.current++;
    setState(initial(identity)); if (identity) void reloadHistory(true);
    return () => {
      for (const controller of controllers.current) controller.abort(); controllers.current.clear(); requests.current.clear();
      for (const timer of timers.current.values()) clearTimeout(timer); timers.current.clear();
    };
  }, [identity]);
  useEffect(() => {
    if (identity && state.identity === identity && state.hydrated && !state.loading) {
      try { localStorage.setItem(selectionKey(identity), JSON.stringify({ conversationId: state.activeId, projectId: state.projectId })); }
      catch { /* Active selection is optional; conversations are saved by the server. */ }
    }
  }, [identity, state.identity, state.hydrated, state.loading, state.activeId, state.projectId]);

  const schedulePoll = (id: string, token: typeof session.current) => {
    clearTimer(id);
    if (valid(token)) timers.current.set(id, setTimeout(() => { timers.current.delete(id); if (valid(token)) void loadThread(id, true); }, 1600));
  };
  const loadThread = async (id: string, polling = false) => {
    const token = session.current; if (!valid(token) || requests.current.has(id)) return;
    clearTimer(id); const controller = newController(); requests.current.set(id, { controller });
    updateThread(token, id, thread => ({ ...thread, loading: !polling, loadError: '' }));
    try {
      const questions = await chatApi.questions(id, controller.signal);
      if (!controller.signal.aborted) {
        updateThread(token, id, thread => {
          const accepted = !!thread.unsentDraft && questions.slice(thread.unsentAfter ?? thread.questionCount ?? 0).some(question => question.question === thread.unsentDraft);
          return { ...thread, loaded: true, loading: false, loadError: '', questionCount: questions.length, messages: questions.flatMap(questionMessages), unsentDraft: accepted ? undefined : thread.unsentDraft, unsentAfter: accepted ? undefined : thread.unsentAfter };
        });
        if (questions.some(question => question.status === 'pending')) schedulePoll(id, token);
      }
    } catch { if (!controller.signal.aborted) updateThread(token, id, thread => ({ ...thread, loading: false, loadError: 'Could not load the saved conversation. Reload it before sending another message.' })); }
    finally { if (requests.current.get(id)?.controller === controller) requests.current.delete(id); controllers.current.delete(controller); }
  };
  const newChat = (projectId: string | null = project.current) => {
    focus.current++; active.current = null; project.current = projectId;
    update(session.current, previous => ({ ...previous, activeId: null, projectId, error: '' }));
  };
  const selectChat = (id: string) => {
    const thread = stateRef.current.threads.find(item => item.id === id); if (!thread || thread.deleting) return;
    const projectId = stateRef.current.projects.some(item => item.id === thread.projectId) ? thread.projectId ?? null : null;
    focus.current++; active.current = id; project.current = projectId;
    update(session.current, previous => ({ ...previous, activeId: id, projectId }));
    void loadThread(id);
  };
  const createProject = async (name: string) => {
    const token = session.current; const clean = name.trim(); if (!valid(token) || !clean || clean.length > 80 || projectSaving.current) return false;
    const selectedAtStart = focus.current; const controller = newController(); projectSaving.current = true;
    update(token, previous => ({ ...previous, projectSaving: true, projectError: '' }));
    try {
      const saved = await chatApi.createProject(clean, controller.signal);
      if (!valid(token) || controller.signal.aborted) return false;
      const select = focus.current === selectedAtStart;
      if (select) { active.current = null; project.current = saved.id; focus.current++; }
      update(token, previous => ({ ...previous, projects: [...previous.projects, saved], projectId: select ? saved.id : previous.projectId, activeId: select ? null : previous.activeId })); return true;
    } catch { if (!controller.signal.aborted) update(token, previous => ({ ...previous, projectError: 'Could not save the project. Please try again.' })); return false; }
    finally { controllers.current.delete(controller); if (valid(token)) { projectSaving.current = false; update(token, previous => ({ ...previous, projectSaving: false })); } }
  };
  const deleteChat = async (id: string) => {
    const token = session.current; if (!valid(token) || stateRef.current.threads.find(thread => thread.id === id)?.deleting) return;
    const controller = newController(); updateThread(token, id, thread => ({ ...thread, deleting: true }));
    try {
      await chatApi.remove(id, controller.signal); if (!valid(token) || controller.signal.aborted) return;
      cancelThread(id); if (active.current === id) { active.current = null; focus.current++; }
      update(token, previous => ({ ...previous, activeId: previous.activeId === id ? null : previous.activeId, threads: previous.threads.filter(thread => thread.id !== id) }));
    } catch { if (!controller.signal.aborted) { updateThread(token, id, thread => ({ ...thread, deleting: false })); update(token, previous => ({ ...previous, error: 'Could not delete this conversation. It remains saved; try again after any running response finishes.' })); } }
    finally { controllers.current.delete(controller); }
  };
  const stop = () => {
    const id = active.current; if (!id) return;
    cancelThread(id); updateThread(session.current, id, thread => ({ ...thread, loading: false, messages: thread.messages.map(message => message.status === 'pending' ? { ...message, status: 'stopped' } : message) }));
  };
  const send = async (input: string): Promise<boolean> => {
    const token = session.current; const question = input.trim();
    if (!valid(token) || !question || question.length > 4000 || stateRef.current.loading || (active.current && requests.current.has(active.current))) return false;
    let thread = stateRef.current.threads.find(item => item.id === active.current);
    if (thread && (requests.current.has(thread.id) || thread.loading || !thread.loaded || thread.loadError || thread.deleting || thread.messages.some(message => message.status === 'pending'))) return false;
    if (!thread && creating.current) return false;
    const controller = newController(); const selectedAtStart = focus.current; const projectId = project.current;
    if (!thread) {
      creating.current = true; update(token, previous => ({ ...previous, creating: true, error: '' }));
      try {
        const saved = await chatApi.create(question.slice(0, 120), projectId, controller.signal);
        if (!valid(token) || controller.signal.aborted) return false;
        thread = { ...saved, messages: [], loaded: true };
        const select = focus.current === selectedAtStart;
        if (select) active.current = saved.id;
        const added = thread;
        update(token, previous => ({ ...previous, threads: [added, ...previous.threads], activeId: select ? saved.id : previous.activeId }));
      } catch { if (!controller.signal.aborted) update(token, previous => ({ ...previous, error: 'Could not save a new conversation. Your message has not been sent. Please retry.' })); controllers.current.delete(controller); return false; }
      finally { if (valid(token)) { creating.current = false; update(token, previous => ({ ...previous, creating: false })); } }
    }
    const id = thread.id; const optimisticId = crypto.randomUUID(); const previousCount = thread.questionCount ?? 0;
    const optimistic: ChatMessage[] = [{ id: `${optimisticId}:user`, role: 'user', text: question, status: 'complete' }, { id: optimisticId, role: 'assistant', text: '', status: 'pending' }];
    clearTimer(id); requests.current.set(id, { controller });
    updateThread(token, id, current => ({ ...current, messages: [...current.messages, ...optimistic], loadError: '', unsentDraft: undefined, unsentAfter: undefined }));
    try {
      const saved = await chatApi.ask(id, question, controller.signal);
      if (!controller.signal.aborted) {
        updateThread(token, id, current => ({ ...current, questionCount: previousCount + 1, messages: [...current.messages.filter(message => !optimistic.some(item => item.id === message.id)), ...questionMessages(saved)] }));
        if (saved.status === 'pending') schedulePoll(id, token);
      }
    } catch (cause) {
      if (!controller.signal.aborted && valid(token)) {
        try {
          const stored = await chatApi.questions(id, controller.signal);
          if (!controller.signal.aborted) {
            const rejected = cause instanceof ApiError && [400, 403, 404, 409, 429].includes(cause.status);
            const error = cause instanceof ApiError && cause.status === 409 ? 'Another response is still running. Wait for it to finish before retrying.' : 'This message could not be saved. Please try again.';
            const pending = stored.some(item => item.status === 'pending');
            const restoreDraft = rejected || (stored.length <= previousCount && !pending);
            updateThread(token, id, current => ({ ...current, loaded: true, questionCount: stored.length, messages: [...stored.flatMap(questionMessages), ...(stored.length <= previousCount && !pending ? [optimistic[0], { ...optimistic[1], status: 'error' as const, error: rejected ? error : 'Could not confirm that this message was saved. Reload this chat before retrying.' }] : [])], loadError: !rejected && stored.length <= previousCount && !pending ? 'Could not confirm the message was saved. Reload the conversation before retrying.' : '', unsentDraft: restoreDraft ? question : undefined, unsentAfter: restoreDraft ? rejected ? stored.length : previousCount : undefined }));
            if (pending) schedulePoll(id, token);
            if (restoreDraft) return false;
          }
        } catch {
          if (!controller.signal.aborted) {
            updateThread(token, id, current => ({ ...current, loadError: 'Could not confirm the message was saved. Reload the conversation before retrying.', unsentDraft: question, unsentAfter: previousCount, messages: current.messages.map(message => message.id === optimisticId ? { ...message, status: 'error', error: 'Connection lost while saving the response. The server may still be processing it.' } : message) }));
            return false;
          }
        }
      }
    } finally { if (requests.current.get(id)?.controller === controller) requests.current.delete(id); controllers.current.delete(controller); }
    return true;
  };
  const visible = state.identity === identity ? state : initial(identity);
  const activeThread = visible.threads.find(thread => thread.id === visible.activeId);
  return {
    threads: visible.threads, projects: visible.projects, activeId: visible.activeId, activeThread,
    selectedProjectId: visible.projectId, selectedProjectName: visible.projects.find(item => item.id === visible.projectId)?.name ?? 'General',
    newChat, selectProject: newChat, selectChat, deleteChat, createProject, send, stop, reloadHistory,
    reloadActive: () => { if (active.current) void loadThread(active.current); },
    historyLoading: visible.loading, historyError: visible.error, creating: visible.creating, projectSaving: visible.projectSaving, projectError: visible.projectError,
    busy: activeThread?.messages.some(message => message.status === 'pending') ?? false,
  };
}
