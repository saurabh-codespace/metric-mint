import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useReducedMotion } from 'motion/react';
import { ArrowRight, ArrowUp, BarChart3, ChevronDown, Clock3, Database, FileText, RefreshCw, ShieldCheck, Square, TriangleAlert } from 'lucide-react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { ChatAnswer } from './api/chat';
import type { ReportDatum } from './api/client';
import type { ChatThread } from './chatState';
export type { ChatThread, ChatMessage } from './chatState';

export { useChatThreads } from './useChatThreads';

const starters = [
  { icon: BarChart3, text: 'Show monthly invoice volume', caption: 'Find the bigger picture' },
  { icon: Database, text: 'Which LOB has the most open invoices?', caption: 'Explore your workload' },
  { icon: FileText, text: 'Show invoice status distribution', caption: 'Understand payment health' },
  { icon: TriangleAlert, text: 'Show the latest invoice in the duplicate queue', caption: 'Check its current assignment' },
];

export function ChatView({ thread, onSend, onStop, busy, projectName = 'General', creating = false, historyLoading = false, onReload, error }: { thread: ChatThread | undefined; onSend: (question: string) => Promise<boolean>; onStop: () => void; busy: boolean; projectName?: string; creating?: boolean; historyLoading?: boolean; onReload: () => void; error?: string }) {
  const [draft, setDraft] = useState('');
  const textarea = useRef<HTMLTextAreaElement>(null);
  const scroll = useRef<HTMLDivElement>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const follow = useRef(true);
  const restoredDraft = useRef<string | undefined>(undefined);
  const reduced = useReducedMotion();
  useEffect(() => { setDraft(''); follow.current = true; textarea.current?.focus(); }, [thread?.id]);
  useEffect(() => {
    const previous = restoredDraft.current;
    const unsent = thread?.unsentDraft;
    if (unsent) setDraft(current => current || unsent);
    else if (previous) setDraft(current => current === previous ? '' : current);
    restoredDraft.current = unsent;
  }, [thread?.unsentDraft]);
  useEffect(() => { if (follow.current) bottom.current?.scrollIntoView({ behavior: reduced || busy ? 'instant' : 'smooth', block: 'end' }); }, [thread?.messages, busy, reduced]);
  useEffect(() => { if (textarea.current) { textarea.current.style.height = 'auto'; textarea.current.style.height = `${Math.min(textarea.current.scrollHeight, 180)}px`; } }, [draft]);
  const blocked = busy || creating || historyLoading || !!thread?.loading || !!thread?.loadError || !!thread?.deleting;
  const submit = (event?: FormEvent) => {
    event?.preventDefault(); const question = draft.trim(); if (!question || blocked) return;
    follow.current = true; setDraft(''); void onSend(question).then(sent => { if (!sent) setDraft(current => current || question); });
  };
  return <section className={`chat-view ${thread?.messages.length ? 'has-messages' : 'is-empty'}`} aria-label="Chat with Metric Mint">
    <div className="chat-scroll" ref={scroll} onScroll={() => { const element = scroll.current; if (element) follow.current = element.scrollHeight - element.scrollTop - element.clientHeight < 100; }}>
      {thread?.loading && <p className="chat-loading-history" role="status">Loading saved conversation…</p>}
      {thread?.loadError && <div className="chat-load-error" role="alert"><p>{thread.loadError}</p><button className="secondary" onClick={onReload}>Reload conversation</button></div>}
      {!thread?.messages.length ? <div className="chat-welcome"><span className="chat-welcome-mark"><img src="/metric-mint-mark.svg" alt="Metric Mint logo" width="55" height="55" /></span><h1>What would you like to explore?</h1><p>Ask about your invoices, find a pattern, or take a closer look at your data.</p><div className="chat-starters">{starters.map(starter => <button key={starter.text} disabled={blocked} onClick={() => { follow.current = true; void onSend(starter.text).then(sent => { if (!sent) setDraft(current => current || starter.text); }); }}><starter.icon size={19} /><strong>{starter.text}</strong><span>{starter.caption}<ArrowRight size={13} /></span></button>)}</div></div>
        : <div className="chat-transcript" role="log" aria-label="Conversation" aria-live="polite" aria-relevant="additions text">{thread.messages.map((message, index) => <article className={`chat-message ${message.role}`} key={message.id} aria-label={message.role === 'user' ? 'You' : 'Metric Mint'}>{message.role === 'user' ? <div className="chat-user-bubble">{message.text}</div> : <><div className="chat-assistant-label"><span><img src="/metric-mint-mark.svg" alt="" width="26" height="26" /></span><strong>Metric Mint</strong>{message.answer && <small>{message.answer.aiGenerated ? 'AI-assisted analysis' : message.answer.lineage?.metricStatus === 'OPERATIONAL' ? 'Operational lookup' : message.answer.query ? 'Governed analytics' : 'Backend analytics'}</small>}</div><div className="chat-assistant-body">{message.status === 'pending' ? <div className="chat-thinking" role="status"><span><i /><i /><i /></span>Checking your workspace data…</div> : message.status === 'error' ? <div className="chat-response-error"><p>{message.error}</p><button disabled={blocked} onClick={() => { const question = thread.messages.slice(0, index).reverse().find(item => item.role === 'user')?.text; if (question) void onSend(question); }}><RefreshCw size={14} /> Try again</button></div> : message.status === 'stopped' ? <p className="chat-stopped">Stopped waiting. The server may finish this response; reopen the chat to load it.</p> : message.answer && <AnswerView answer={message.answer} />}</div></>}</article>)}</div>}
      <div ref={bottom} />
    </div>
    <div className="chat-composer-area">{error && <p className="chat-save-error" role="alert">{error}</p>}<form className="chat-composer" onSubmit={submit}><textarea ref={textarea} aria-label="Message Metric Mint" placeholder="Ask anything about your data" rows={1} maxLength={4000} value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) { event.preventDefault(); submit(); } }} /><div className="chat-composer-bottom"><span><Database size={13} /> <span className="chat-project-context">{projectName}</span>{creating && <span role="status"> · Saving new chat…</span>}</span>{busy ? <button type="button" className="chat-send" onClick={onStop} aria-label="Stop response" title="Stop response"><Square size={16} fill="currentColor" /></button> : <button type="submit" className="chat-send" disabled={!draft.trim() || blocked} aria-label="Send message" title="Send message"><ArrowUp size={20} /></button>}</div></form><p className="chat-composer-note">Answers use your permitted data. Check the evidence before making decisions.</p></div>
  </section>;
}

function AnswerView({ answer }: { answer: ChatAnswer }) {
  const rows = answer.rows ?? [];
  const operational = answer.lineage?.metricStatus === 'OPERATIONAL';
  return <div className="chat-answer"><p className="chat-answer-text">{answer.answer}</p>{answer.explanation && answer.explanation !== answer.answer && <p className="chat-answer-explanation">{answer.explanation}</p>}{answer.clarification && answer.clarification !== answer.answer && <p className="chat-clarification">{answer.clarification}</p>}{answer.insights?.length > 0 && <ul className="chat-insights">{answer.insights.map((insight, index) => <li key={index}>{insight}</li>)}</ul>}{answer.warnings?.length > 0 && <div className="chat-warnings">{answer.warnings.map((warning, index) => <p key={index}><TriangleAlert size={14} /><span>{warning}</span></p>)}</div>}{rows.length ? <ChatChart answer={answer} /> : !answer.clarification && answer.query && <div className="chat-empty-data"><Database size={19} /><div><strong>No matching data</strong><p>The query ran successfully but returned no rows. Try a broader date range or another filter.</p></div></div>}
    {(answer.lineage || answer.executedAt || answer.label) && <details className="chat-evidence"><summary><ShieldCheck size={14} /><span>{operational ? 'Operational lookup' : answer.label === 'VERIFIED' ? 'Verified metric' : answer.label === 'EXPLORATORY' ? 'Exploratory result' : 'Query evidence'}</span>{answer.executedAt && <small><Clock3 size={12} /> {new Date(answer.executedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</small>}<ChevronDown size={14} /></summary><div>{answer.executedAt && <p><strong>Executed</strong><span>{new Date(answer.executedAt).toLocaleString()}</span></p>}{answer.lineage && <><p><strong>Source</strong><span>{answer.lineage.source}</span></p><p><strong>Tables</strong><span>{answer.lineage.tables.join(', ')}</span></p><p><strong>{operational ? 'Lookup' : 'Metric'}</strong><span>{answer.lineage.metricDefinition}{!operational && <> · Version {answer.lineage.metricVersion} · {answer.lineage.metricStatus}</>}</span></p><p><strong>Data as of</strong><span>{answer.lineage.dataAsOf || 'Not provided'}</span></p>{answer.lineage.filters.length > 0 && <p><strong>Filters</strong><span>{answer.lineage.filters.join(' · ')}</span></p>}<details><summary>Query details</summary><pre>{answer.lineage.sql}</pre>{answer.lineage.parameters.length > 0 && <p><strong>Parameters</strong><span>{answer.lineage.parameters.join(', ')}</span></p>}</details></>}</div></details>}
  </div>;
}

const label = (key: string) => key.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ').replace(/^./, char => char.toUpperCase());
const colors = ['#238f87', '#638bc6', '#c79765', '#8c7cb4', '#ca7c83'];
function ChatTable({ rows }: { rows: ReportDatum[] }) {
  const columns = Object.keys(rows[0] ?? {});
  return <div className="chat-table"><table><caption className="sr-only">Query result data</caption><thead><tr>{columns.map(column => <th key={column}>{label(column)}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{columns.map(column => <td key={column}>{typeof row[column] === 'number' ? new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(row[column] as number) : String(row[column] ?? '—')}</td>)}</tr>)}</tbody></table></div>;
}

function ChatChart({ answer }: { answer: ChatAnswer }) {
  const reduced = useReducedMotion();
  const keys = Object.keys(answer.rows[0] ?? {});
  const numericKeys = keys.filter(key => answer.rows.some(row => typeof row[key] === 'number'));
  const valueKey = answer.query?.metric && numericKeys.includes(answer.query.metric) ? answer.query.metric : numericKeys.length === 1 ? numericKeys[0] : undefined;
  const labelKey = keys.find(key => key !== valueKey && answer.rows.some(row => typeof row[key] === 'string')) ?? keys.find(key => key !== valueKey);
  const supported = ['bar', 'column', 'line', 'area', 'pie', 'donut'].includes(answer.visualization);
  if (!valueKey || !supported || (['pie', 'donut'].includes(answer.visualization) && answer.rows.some(row => typeof row[valueKey] === 'number' && (row[valueKey] as number) < 0))) return <ChatTable rows={answer.rows} />;
  const name = (row: ReportDatum) => labelKey ? row[labelKey] ?? '—' : 'Result';
  const value = (row: ReportDatum) => row[valueKey] as number;
  const tip = <Tooltip contentStyle={{ background: 'var(--surface)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 10, fontSize: 12 }} />;
  const axes = <><CartesianGrid vertical={false} stroke="var(--line)" strokeDasharray="3 5" /><XAxis dataKey={name} tick={{ fontSize: 11, fill: 'var(--muted)' }} tickLine={false} axisLine={false} /><YAxis tick={{ fontSize: 11, fill: 'var(--muted)' }} width={48} tickLine={false} axisLine={false} /></>;
  return <div className="chat-result"><header><span><BarChart3 size={15} />{label(valueKey)}{labelKey ? ` by ${label(labelKey).toLowerCase()}` : ''}</span><small>{answer.rows.length} rows</small></header><div className="chat-chart" role="img" aria-label={`${label(valueKey)} chart from ${answer.rows.length} query result rows`}><ResponsiveContainer width="100%" height="100%">{answer.visualization === 'pie' || answer.visualization === 'donut' ? <PieChart><Pie data={answer.rows} dataKey={value} nameKey={name} innerRadius={answer.visualization === 'donut' ? '50%' : 0} outerRadius="80%" paddingAngle={2} isAnimationActive={!reduced}>{answer.rows.map((_, index) => <Cell key={index} fill={colors[index % colors.length]} />)}</Pie>{tip}</PieChart> : answer.visualization === 'line' ? <LineChart data={answer.rows} margin={{ top: 12, right: 16, bottom: 10 }}>{axes}{tip}<Line dataKey={value} name={label(valueKey)} type="monotone" stroke={colors[0]} strokeWidth={2.5} dot={answer.rows.length < 20} isAnimationActive={!reduced} /></LineChart> : answer.visualization === 'area' ? <AreaChart data={answer.rows} margin={{ top: 12, right: 16, bottom: 10 }}>{axes}{tip}<Area dataKey={value} name={label(valueKey)} type="monotone" stroke={colors[0]} strokeWidth={2.5} fill={colors[0]} fillOpacity={.12} isAnimationActive={!reduced} /></AreaChart> : <BarChart data={answer.rows} layout={answer.visualization === 'bar' ? 'vertical' : 'horizontal'} margin={{ top: 12, right: 16, bottom: 10 }}>{answer.visualization === 'bar' ? <><CartesianGrid horizontal={false} stroke="var(--line)" strokeDasharray="3 5" /><XAxis type="number" tick={{ fontSize: 11, fill: 'var(--muted)' }} /><YAxis type="category" dataKey={name} width={100} tick={{ fontSize: 11, fill: 'var(--muted)' }} /></> : axes}{tip}<Bar dataKey={value} name={label(valueKey)} fill={colors[0]} radius={4} maxBarSize={32} isAnimationActive={!reduced} /></BarChart>}</ResponsiveContainer></div><details className="chat-data-details"><summary><FileText size={14} /> View result data <ChevronDown size={13} /></summary><ChatTable rows={answer.rows} /></details></div>;
}
