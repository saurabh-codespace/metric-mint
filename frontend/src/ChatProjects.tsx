import { useEffect, useState } from 'react';
import { ChevronDown, Folder, MessageSquareText, Plus, SquarePen, Trash2 } from 'lucide-react';
import type { useChatThreads } from './useChatThreads';

export function ChatProjects({ chat, active, onNavigate }: { chat: ReturnType<typeof useChatThreads>; active: boolean; onNavigate: () => void }) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [expanded, setExpanded] = useState(new Set(['general']));
  useEffect(() => { setExpanded(previous => new Set(previous).add(chat.selectedProjectId ?? 'general')); }, [chat.selectedProjectId]);
  const groups = [{ id: null, name: 'General' }, ...chat.projects];
  return <section className="chat-history" aria-label="Conversation history"><div className="chat-projects-heading"><h2>Projects</h2><button disabled={chat.historyLoading || chat.projectSaving} onClick={() => setAdding(true)} aria-label="New project" title="New project"><Plus size={15} /></button></div>
    {adding && <form className="chat-project-form" onSubmit={event => { event.preventDefault(); void chat.createProject(name).then(saved => { if (saved) { setAdding(false); setName(''); } }); }}><label>Project name<input autoFocus required maxLength={80} value={name} disabled={chat.projectSaving} onChange={event => setName(event.target.value)} placeholder="e.g. Invoice operations" /></label><div><button type="submit" disabled={chat.projectSaving || !name.trim()}>{chat.projectSaving ? 'Saving project…' : 'Create project'}</button><button type="button" disabled={chat.projectSaving} onClick={() => { setAdding(false); setName(''); }}>Cancel project</button></div>{chat.projectError && <p role="alert">{chat.projectError}</p>}</form>}
    {chat.historyLoading && <p role="status">Loading saved chats…</p>}
    {chat.historyError && <div className="chat-history-error" role="alert"><p>{chat.historyError}</p><button disabled={chat.historyLoading || chat.creating || chat.projectSaving} onClick={() => void chat.reloadHistory()}>Retry history</button></div>}
    {groups.map(group => {
      const key = group.id ?? 'general'; const open = expanded.has(key);
      const threads = chat.threads.filter(thread => group.id ? thread.projectId === group.id : !thread.projectId || !chat.projects.some(project => project.id === thread.projectId));
      return <div className={`chat-project ${chat.selectedProjectId === group.id ? 'selected' : ''}`} data-project-id={key} key={key}><div className="chat-project-heading"><button className="chat-project-select" aria-label={`Open project ${group.name}`} aria-expanded={open} disabled={chat.historyLoading} onClick={() => {
        setExpanded(previous => { const next = new Set(previous); if (open) next.delete(key); else next.add(key); return next; });
        if (!open || chat.selectedProjectId !== group.id) { chat.selectProject(group.id); onNavigate(); }
      }}><ChevronDown size={12} /><Folder size={15} /><span>{group.name}</span><small>{threads.length}</small></button><button className="chat-project-new" aria-label={`New chat in ${group.name}`} title={`New chat in ${group.name}`} disabled={chat.historyLoading || chat.creating} onClick={() => { setExpanded(previous => new Set(previous).add(key)); chat.newChat(group.id); onNavigate(); }}><SquarePen size={14} /></button></div>
        {open && <div className="chat-project-threads">{threads.map(thread => <div key={thread.id} data-conversation-id={thread.id} className={`chat-history-item ${active && chat.activeId === thread.id ? 'active' : ''}`}><button className="chat-history-select" disabled={thread.deleting} title={thread.title} aria-current={active && chat.activeId === thread.id ? 'page' : undefined} onClick={() => { chat.selectChat(thread.id); onNavigate(); }}><MessageSquareText size={14} /><span>{thread.title}</span>{thread.messages.some(message => message.status === 'pending') && <span className="chat-history-pending" aria-label="Response in progress" />}</button><button className="chat-history-delete" disabled={thread.deleting || chat.historyLoading} aria-label={`Delete chat ${thread.title}`} onClick={() => { if (window.confirm('Delete this saved conversation?')) void chat.deleteChat(thread.id); }}><Trash2 size={13} /></button></div>)}{!threads.length && !chat.historyLoading && <p className="chat-project-empty">No chats yet.</p>}</div>}
      </div>;
    })}
    <small>Chats are saved to your account.</small>
  </section>;
}
