import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { CheckCircle2, Download, Play, Save, ShieldCheck, TriangleAlert, Upload } from 'lucide-react';
import { ApiError, type DataSource } from './api/client';
import {
  download, governed, type AuditEvent, type CatalogTable, type CsvPreview, type Metric, type MetricOption, type MetricVersion, type Policy, type QueryResult,
  type QuerySpec, type Relationship, type RunView, type SavedReport, type UserView, type WorkspaceView,
} from './api/governed';
import './governed.css';

function useLoad<T>(loader: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try { setData(await loader()); } catch (e) { setError(message(e)); } finally { setLoading(false); }
  }, deps);
  useEffect(() => { void load(); }, [load]);
  return { data, error, loading, reload: load };
}

function message(e: unknown): string {
  if (e instanceof ApiError) {
    try { const body = JSON.parse(e.message) as { detail?: string }; if (body.detail) return body.detail; } catch { /* plain text */ }
    return e.status === 403 ? 'Your role does not allow this action.' : e.message;
  }
  return 'The request failed. Check that the backend is running.';
}

const fmt = (v: unknown) => typeof v === 'number' ? new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(v) : String(v ?? '');
const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString() : '—';

function Notice({ error, children }: { error?: string; children?: ReactNode }) {
  if (error) return <p className="gv-error" role="alert"><TriangleAlert size={14} /> {error}</p>;
  return children ? <p className="gv-ok" role="status"><CheckCircle2 size={14} /> {children}</p> : null;
}

function Panel({ title, caption, action, children }: { title: string; caption?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="card gv-panel"><header><div><h2>{title}</h2>{caption && <p>{caption}</p>}</div>{action}</header>{children}</section>;
}

function ResultView({ result }: { result: QueryResult }) {
  const dims = result.columns.slice(0, -1);
  const key = result.metric;
  const max = Math.max(...result.rows.map(r => Math.abs(Number(r[key]) || 0)), 1);
  return <div className="gv-result">
    <div className="gv-result-head">
      <strong>{result.metricName}</strong>
      <span className={`gv-badge ${result.label === 'VERIFIED' ? 'ok' : 'warn'}`}>{result.label === 'VERIFIED' ? <><ShieldCheck size={12} /> Verified</> : 'Exploratory'}</span>
      <small>Run {when(result.executedAt)} · data as of {when(result.lineage.dataAsOf)}</small>
    </div>
    {result.warnings.map(w => <p key={w} className="gv-warning"><TriangleAlert size={13} /> {w}</p>)}
    {dims.length > 0 && result.rows.length > 0 && <div className="gv-bars" role="img" aria-label={`${result.metricName} by ${dims.join(', ')}`}>
      {result.rows.slice(0, 15).map((row, i) => <div key={i}><span>{dims.map(d => String(row[d] ?? '—')).join(' / ')}</span><i><b style={{ width: `${Math.max(2, Math.abs(Number(row[key]) || 0) / max * 100)}%` }} /></i><strong>{fmt(row[key])}</strong></div>)}
    </div>}
    <div className="gv-table-wrap"><table><thead><tr>{result.columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
      <tbody>{result.rows.map((row, i) => <tr key={i}>{result.columns.map(c => <td key={c}>{fmt(row[c])}</td>)}</tr>)}</tbody></table></div>
    <details className="gv-lineage"><summary>How this was calculated</summary>
      <dl>
        <dt>Metric</dt><dd>{result.lineage.metricDefinition} (v{result.lineage.metricVersion}, {result.lineage.metricStatus.toLowerCase()}{result.lineage.metricVerified ? ', verified' : ''})</dd>
        <dt>Source</dt><dd>{result.lineage.source} — {result.lineage.tables.join(', ')}</dd>
        <dt>Filters</dt><dd>{result.lineage.filters.length ? result.lineage.filters.join('; ') : 'None'}</dd>
        <dt>SQL</dt><dd><pre>{result.lineage.sql}</pre>{result.lineage.parameters.length > 0 && <small>Parameters: {result.lineage.parameters.join(', ')}</small>}</dd>
      </dl>
    </details>
  </div>;
}

export function Explore() {
  const options = useLoad(governed.options);
  const reports = useLoad(governed.reports);
  const [metricKey, setMetricKey] = useState('');
  const [dims, setDims] = useState<string[]>([]);
  const [grain, setGrain] = useState('month');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [filterColumn, setFilterColumn] = useState('');
  const [filterValue, setFilterValue] = useState('');
  const [limit, setLimit] = useState(100);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [question, setQuestion] = useState('');
  const [chatNote, setChatNote] = useState('');
  const [reportName, setReportName] = useState('');
  const [shared, setShared] = useState(false);
  const [saved, setSaved] = useState('');

  const metric: MetricOption | undefined = useMemo(() => options.data?.find(m => m.key === (metricKey || options.data?.[0]?.key)), [options.data, metricKey]);
  useEffect(() => { setDims([]); setFilterColumn(''); }, [metric?.key]);

  const spec = (): QuerySpec => ({
    metric: metric!.key, dimensions: dims, timeGrain: dims.includes('time') ? grain : null, limit,
    dateRange: from || to ? { from: from || null, to: to || null } : null,
    filters: filterColumn && filterValue ? [{ column: filterColumn, op: 'EQ', values: [filterValue] }] : [],
  });
  const run = async (next?: QuerySpec) => {
    setBusy(true); setError(''); setSaved('');
    try { setResult(await governed.runQuery(next ?? spec())); } catch (e) { setError(message(e)); setResult(null); } finally { setBusy(false); }
  };
  const ask = async (event: FormEvent) => {
    event.preventDefault();
    if (!question.trim()) return;
    setBusy(true); setError(''); setChatNote(''); setSaved('');
    try {
      const answer = await governed.ask(question, result?.spec);
      if (answer.clarification) { setChatNote(answer.clarification); } else if (answer.result) { setResult(answer.result); setChatNote(`Interpreted as: ${answer.interpretation}`); }
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const save = async () => {
    if (!result || !reportName.trim()) return;
    try { await governed.saveReport({ name: reportName, visualization: 'bar', query: result.spec, shared }); setSaved('Report saved.'); setReportName(''); void reports.reload(); }
    catch (e) { setError(message(e)); }
  };
  const toggleDim = (ref: string) => setDims(current => current.includes(ref) ? current.filter(d => d !== ref) : current.length < 3 ? [...current, ref] : current);

  return <div className="gv-grid">
    <Panel title="Ask in plain language" caption="Questions are mapped to approved metrics only. If something is unclear you will be asked, not guessed at.">
      <form className="gv-row" onSubmit={event => void ask(event)}>
        <input value={question} onChange={e => setQuestion(e.target.value)} placeholder="e.g. net revenue by region last quarter — then: what about by month?" aria-label="Question" />
        <button className="primary" disabled={busy || !question.trim()}><Play size={15} /> Ask</button>
      </form>
      {chatNote && <p className="gv-note">{chatNote}</p>}
    </Panel>

    <Panel title="Explore" caption="Pick a governed metric, how to group it and filter it. Every change re-runs a permission-checked query.">
      {options.loading ? <p className="gv-note">Loading metrics…</p> : options.error ? <Notice error={options.error} /> : !options.data?.length ? <p className="gv-note">No metric is available yet. An analyst must define and approve one in Governance.</p> : <>
        <div className="gv-form">
          <label>Metric<select value={metric?.key ?? ''} onChange={e => setMetricKey(e.target.value)}>{options.data.map(m => <option key={m.key} value={m.key}>{m.name}{m.verified ? ' ✓' : m.status === 'DRAFT' ? ' (draft)' : ''}</option>)}</select></label>
          <fieldset><legend>Group by (up to 3)</legend>
            <div className="gv-chips">
              {metric?.hasTime && <label className={dims.includes('time') ? 'on' : ''}><input type="checkbox" checked={dims.includes('time')} onChange={() => toggleDim('time')} />time</label>}
              {metric?.dimensions.map(d => <label key={d.ref} className={dims.includes(d.ref) ? 'on' : ''}><input type="checkbox" checked={dims.includes(d.ref)} onChange={() => toggleDim(d.ref)} />{d.ref}</label>)}
            </div>
          </fieldset>
          {dims.includes('time') && <label>Time grain<select value={grain} onChange={e => setGrain(e.target.value)}><option>day</option><option>month</option><option>year</option></select></label>}
          {metric?.hasTime && <><label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label><label>Up to (excl.)<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label></>}
          <label>Filter column<select value={filterColumn} onChange={e => setFilterColumn(e.target.value)}><option value="">None</option>{metric?.dimensions.map(d => <option key={d.ref} value={d.ref}>{d.ref}</option>)}</select></label>
          {filterColumn && <label>Equals<input value={filterValue} onChange={e => setFilterValue(e.target.value)} /></label>}
          <label>Row limit<input type="number" min={1} max={10000} value={limit} onChange={e => setLimit(Number(e.target.value))} /></label>
        </div>
        <button className="primary" disabled={busy || !metric} onClick={() => void run()}><Play size={15} /> {busy ? 'Running…' : 'Run'}</button>
      </>}
      <Notice error={error} />
    </Panel>

    {result && <Panel title="Result" action={<div className="gv-row">
      <input value={reportName} onChange={e => setReportName(e.target.value)} placeholder="Report name" aria-label="Report name" />
      <label className="gv-inline"><input type="checkbox" checked={shared} onChange={e => setShared(e.target.checked)} /> share with workspace</label>
      <button className="secondary" onClick={() => void save()} disabled={!reportName.trim()}><Save size={13} /> Save report</button></div>}>
      <ResultView result={result} />
      <Notice>{saved}</Notice>
    </Panel>}

    <Panel title="Saved reports" caption="Shared reports run with the viewer's own access, never the author's.">
      {reports.error ? <Notice error={reports.error} /> : !reports.data?.length ? <p className="gv-note">No saved reports yet.</p> :
        <ul className="gv-list">{reports.data.map((r: SavedReport) => <li key={r.id}>
          <span><strong>{r.name}</strong><small>{r.shared ? 'Shared' : 'Private'} · {r.owner}</small></span>
          <span className="gv-actions">
            <button className="secondary" onClick={() => governed.runReport(r.id).then(setResult).catch(e => setError(message(e)))}><Play size={12} /> Run</button>
            <button className="secondary" onClick={() => void download(`/reports/${r.id}/export?format=csv`, `${r.name}.csv`).catch(e => setError(message(e)))}><Download size={12} /> CSV</button>
            <button className="secondary" onClick={() => void download(`/reports/${r.id}/export?format=pdf`, `${r.name}.pdf`).catch(e => setError(message(e)))}><Download size={12} /> PDF</button>
            {r.mine && <button className="secondary" onClick={() => governed.deleteReport(r.id).then(() => reports.reload()).catch(e => setError(message(e)))}>Delete</button>}
          </span></li>)}</ul>}
    </Panel>
  </div>;
}

type Tab = 'metrics' | 'catalog' | 'access' | 'usage';

export function Governance() {
  const [tab, setTab] = useState<Tab>('metrics');
  return <>
    <div className="filter-tabs gv-tabs" role="tablist">{(['metrics', 'catalog', 'access', 'usage'] as Tab[]).map(t => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>{{ metrics: 'Metrics', catalog: 'Catalogue & joins', access: 'Access & members', usage: 'Usage, limits & audit' }[t]}</button>)}</div>
    {tab === 'metrics' && <MetricsAdmin />}{tab === 'catalog' && <CatalogAdmin />}{tab === 'access' && <AccessAdmin />}{tab === 'usage' && <UsageAdmin />}
  </>;
}

const blankMetric = { name: '', keyName: '', description: '', aggregation: 'SUM', sourceField: '', active: true, tableId: '' as string | null, dateColumn: '' as string | null, baselineValue: '' as string | number | null, synonyms: '' };

function MetricsAdmin() {
  const metrics = useLoad(governed.metrics);
  const tables = useLoad(governed.tables);
  const [form, setForm] = useState(blankMetric);
  const [feedback, setFeedback] = useState<{ error?: string; ok?: string }>({});
  const [versions, setVersions] = useState<{ metric: Metric; list: MetricVersion[] } | null>(null);
  const table = tables.data?.find(t => t.id === form.tableId);
  const act = async (label: string, fn: () => Promise<unknown>) => {
    try { await fn(); setFeedback({ ok: label }); void metrics.reload(); } catch (e) { setFeedback({ error: message(e) }); }
  };
  const create = (event: FormEvent) => {
    event.preventDefault();
    void act('Metric saved as a draft. Approve it to publish.', async () => {
      await governed.createMetric({ ...form, tableId: form.tableId || null, dateColumn: form.dateColumn || null, baselineValue: form.baselineValue === '' || form.baselineValue === null ? null : Number(form.baselineValue),
        synonyms: form.synonyms.split(',').map(s => s.trim()).filter(Boolean) });
      setForm(blankMetric);
    });
  };
  const set = (field: keyof typeof blankMetric, value: string | boolean) => setForm(current => ({ ...current, [field]: value }));
  return <div className="gv-grid">
    <Panel title="Governed metrics" caption="Only an approved definition is used by default; edits become drafts and answers are labelled exploratory until re-approved and verified.">
      <Notice error={feedback.error}>{feedback.ok}</Notice>
      <Notice error={metrics.error} />
      <div className="gv-table-wrap"><table><thead><tr><th>Metric</th><th>Definition</th><th>Status</th><th>Version</th><th /></tr></thead><tbody>
        {metrics.data?.map(m => <tr key={m.id}>
          <td><strong>{m.name}</strong><small className="gv-sub">{m.keyName}</small></td>
          <td><code>{m.aggregation}({m.sourceField})</code></td>
          <td><span className={`gv-badge ${m.verified ? 'ok' : m.status === 'APPROVED' ? 'info' : 'warn'}`}>{m.verified ? 'Verified' : m.status === 'APPROVED' ? 'Approved' : 'Draft'}</span>{m.approvedBy && <small className="gv-sub">by {m.approvedBy}</small>}</td>
          <td>v{m.version}</td>
          <td className="gv-actions">
            <button className="secondary" disabled={m.status === 'APPROVED'} onClick={() => void act('Approved.', () => governed.approveMetric(m.id))}>Approve</button>
            <button className="secondary" disabled={m.status !== 'APPROVED' || m.baselineValue === null} title={m.baselineValue === null ? 'Set a baseline value to enable verification' : ''} onClick={() => void act('Verified against the known total.', () => governed.verifyMetric(m.id))}>Verify</button>
            <button className="secondary" onClick={() => governed.metricVersions(m.id).then(list => setVersions({ metric: m, list })).catch(e => setFeedback({ error: message(e) }))}>History</button>
          </td></tr>)}
      </tbody></table></div>
      {!metrics.data?.length && !metrics.loading && <p className="gv-note">No metrics yet.</p>}
      {versions && <div className="gv-versions"><strong>{versions.metric.name} — history</strong><ul className="gv-list">{versions.list.map((v, i) => <li key={i}><span>v{v.version} · {v.changeType} · {v.status.toLowerCase()} · {v.changedBy} · {when(v.at)}</span>
        {v.version !== versions.metric.version && <button className="secondary" onClick={() => void act(`Rolled back to v${v.version}.`, () => governed.rollbackMetric(versions.metric.id, v.version))}>Restore v{v.version}</button>}</li>)}</ul></div>}
    </Panel>

    <Panel title="New metric" caption="Bind a measure to a catalogue table. The expression may combine numeric columns, e.g. amount - discount.">
      <form className="gv-form" onSubmit={create}>
        <label>Name<input required value={form.name} onChange={e => set('name', e.target.value)} /></label>
        <label>Key<input required pattern="[a-z][a-z0-9_]*" title="lower-case letters, digits, underscore" value={form.keyName} onChange={e => set('keyName', e.target.value)} /></label>
        <label>Description<input required value={form.description} onChange={e => set('description', e.target.value)} /></label>
        <label>Table<select required value={form.tableId ?? ''} onChange={e => set('tableId', e.target.value)}><option value="">Choose…</option>{tables.data?.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
        <label>Aggregation<select value={form.aggregation} onChange={e => set('aggregation', e.target.value)}>{['SUM', 'COUNT', 'COUNT_DISTINCT', 'AVG', 'MIN', 'MAX'].map(a => <option key={a}>{a}</option>)}</select></label>
        <label>Column or expression<input required value={form.sourceField} onChange={e => set('sourceField', e.target.value)} placeholder={table?.columns.find(c => c.role === 'MEASURE')?.name ?? 'amount - discount'} /></label>
        <label>Date column<select value={form.dateColumn ?? ''} onChange={e => set('dateColumn', e.target.value)}><option value="">None</option>{table?.columns.filter(c => c.type === 'DATE' || c.type === 'TIMESTAMP').map(c => <option key={c.name}>{c.name}</option>)}</select></label>
        <label>Known total (baseline)<input type="number" step="any" value={form.baselineValue ?? ''} onChange={e => set('baselineValue', e.target.value)} /></label>
        <label>Synonyms (comma separated)<input value={form.synonyms} onChange={e => set('synonyms', e.target.value)} /></label>
        <button className="primary">Save draft</button>
      </form>
    </Panel>
  </div>;
}

function CatalogAdmin() {
  const tables = useLoad(governed.tables);
  const relationships = useLoad(governed.relationships);
  const [error, setError] = useState('');
  const name = (id: string) => tables.data?.find(t => t.id === id)?.name ?? id.slice(0, 8);
  const toggle = async (t: CatalogTable, column: string, restricted: boolean) => {
    try { await governed.updateTable(t.id, { columns: [{ name: column, sensitivity: restricted ? 'RESTRICTED' : 'PUBLIC' }] }); void tables.reload(); } catch (e) { setError(message(e)); }
  };
  return <div className="gv-grid">
    <Panel title="Data catalogue" caption="Restricted columns are hidden from analysts and viewers and refused in their queries.">
      <Notice error={error || tables.error} />
      {tables.data?.map(t => <details key={t.id} className="gv-table-card"><summary><strong>{t.name}</strong> <small>{fmt(t.rowCount)} rows · loaded {when(t.lastLoadedAt)}</small>{t.stale && <span className="gv-badge warn">stale</span>}</summary>
        <div className="gv-table-wrap"><table><thead><tr><th>Column</th><th>Type</th><th>Role</th><th>Restricted</th></tr></thead><tbody>
          {t.columns.map(c => <tr key={c.name}><td>{c.name}</td><td>{c.type}</td><td>{c.role.toLowerCase()}</td>
            <td><input type="checkbox" aria-label={`Restrict ${c.name}`} checked={c.sensitivity === 'RESTRICTED'} onChange={e => void toggle(t, c.name, e.target.checked)} /></td></tr>)}
        </tbody></table></div></details>)}
      {!tables.data?.length && !tables.loading && <p className="gv-note">No tables yet. Upload a CSV, run a REST/dump source, or register a database table.</p>}
    </Panel>
    <Panel title="Joins" caption="A join is only used by queries after its safety test passes (unique key on the joined side, same source).">
      <ul className="gv-list">{relationships.data?.map((r: Relationship) => <li key={r.id}><span><strong>{name(r.fromTableId)}.{r.fromColumn} → {name(r.toTableId)}.{r.toColumn}</strong><small>{r.cardinality.replace(/_/g, ' ').toLowerCase()} · {r.testMessage}</small></span>
        <span className="gv-actions"><span className={`gv-badge ${r.testStatus === 'SAFE' ? 'ok' : 'warn'}`}>{r.testStatus.toLowerCase()}</span><button className="secondary" onClick={() => governed.testRelationship(r.id).then(() => relationships.reload()).catch(e => setError(message(e)))}>Re-test</button></span></li>)}</ul>
      {!relationships.data?.length && <p className="gv-note">No joins defined.</p>}
    </Panel>
  </div>;
}

function AccessAdmin() {
  const tables = useLoad(governed.tables);
  const policies = useLoad(governed.policies);
  const users = useLoad(governed.users);
  const [policy, setPolicy] = useState({ tableId: '', columnName: '', matchValue: '', role: '' });
  const [user, setUser] = useState({ username: '', displayName: '', password: '', role: 'VIEWER' });
  const [error, setError] = useState('');
  const table = tables.data?.find(t => t.id === policy.tableId);
  const addPolicy = async (event: FormEvent) => { event.preventDefault(); try { await governed.createPolicy({ ...policy, role: policy.role || undefined }); setPolicy({ tableId: '', columnName: '', matchValue: '', role: '' }); setError(''); void policies.reload(); } catch (e) { setError(message(e)); } };
  const addUser = async (event: FormEvent) => { event.preventDefault(); try { await governed.createUser(user); setUser({ username: '', displayName: '', password: '', role: 'VIEWER' }); setError(''); void users.reload(); } catch (e) { setError(message(e)); } };
  return <div className="gv-grid">
    <Panel title="Row-level policies" caption="Enforced by the query service for every query, report, and export. Values are comma-separated; multiple policies on a table all apply.">
      <Notice error={error || policies.error} />
      <ul className="gv-list">{policies.data?.map((p: Policy) => <li key={p.id}><span><strong>{tables.data?.find(t => t.id === p.tableId)?.name ?? p.tableId.slice(0, 8)}.{p.columnName} ∈ {p.matchValue}</strong><small>applies to {p.role ?? p.username ?? 'everyone'}</small></span>
        <button className="secondary" onClick={() => governed.deletePolicy(p.id).then(() => policies.reload()).catch(e => setError(message(e)))}>Remove</button></li>)}</ul>
      <form className="gv-form" onSubmit={e => void addPolicy(e)}>
        <label>Table<select required value={policy.tableId} onChange={e => setPolicy({ ...policy, tableId: e.target.value, columnName: '' })}><option value="">Choose…</option>{tables.data?.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
        <label>Column<select required value={policy.columnName} onChange={e => setPolicy({ ...policy, columnName: e.target.value })}><option value="">Choose…</option>{table?.columns.map(c => <option key={c.name}>{c.name}</option>)}</select></label>
        <label>Allowed values<input required value={policy.matchValue} onChange={e => setPolicy({ ...policy, matchValue: e.target.value })} placeholder="EMEA,APAC" /></label>
        <label>Applies to role<select value={policy.role} onChange={e => setPolicy({ ...policy, role: e.target.value })}><option value="">Everyone</option>{['VIEWER', 'ANALYST', 'ADMIN'].map(r => <option key={r}>{r}</option>)}</select></label>
        <button className="primary">Add policy</button>
      </form>
    </Panel>
    <Panel title="Members" caption="Owner, admin, analyst, viewer. Disabled accounts lose access on their next request.">
      <ul className="gv-list">{users.data?.map((u: UserView) => <li key={u.id}><span><strong>{u.displayName}</strong><small>{u.username}</small></span><span className={`gv-badge ${u.enabled ? 'info' : 'warn'}`}>{u.role.toLowerCase()}{u.enabled ? '' : ' (disabled)'}</span></li>)}</ul>
      <form className="gv-form" onSubmit={e => void addUser(e)}>
        <label>Email / username<input required value={user.username} onChange={e => setUser({ ...user, username: e.target.value })} /></label>
        <label>Display name<input required value={user.displayName} onChange={e => setUser({ ...user, displayName: e.target.value })} /></label>
        <label>Temporary password<input required type="password" minLength={8} autoComplete="new-password" value={user.password} onChange={e => setUser({ ...user, password: e.target.value })} /></label>
        <label>Role<select value={user.role} onChange={e => setUser({ ...user, role: e.target.value })}>{['ADMIN', 'ANALYST', 'VIEWER'].map(r => <option key={r}>{r}</option>)}</select></label>
        <button className="primary">Add member</button>
      </form>
    </Panel>
  </div>;
}

function UsageAdmin() {
  const workspace = useLoad(governed.workspace);
  const audit = useLoad<AuditEvent[]>(governed.audit);
  const [limits, setLimits] = useState<WorkspaceView | null>(null);
  const [feedback, setFeedback] = useState<{ error?: string; ok?: string }>({});
  useEffect(() => { if (workspace.data) setLimits(workspace.data); }, [workspace.data]);
  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!limits) return;
    try { await governed.setLimits(limits); setFeedback({ ok: 'Limits saved.' }); void workspace.reload(); } catch (e) { setFeedback({ error: message(e) }); }
  };
  const num = (field: keyof WorkspaceView) => (e: React.ChangeEvent<HTMLInputElement>) => setLimits(current => current && { ...current, [field]: Number(e.target.value) });
  return <div className="gv-grid">
    <Panel title="Usage and limits" caption="Queries stop once the daily budget is used; each query is also bounded by rows and time.">
      {workspace.data && <p className="gv-meter"><strong>{workspace.data.queriesToday}</strong> of {workspace.data.maxQueriesPerDay} queries used today</p>}
      <Notice error={feedback.error || workspace.error}>{feedback.ok}</Notice>
      {limits && <form className="gv-form" onSubmit={e => void save(e)}>
        <label>Queries per day<input type="number" min={1} value={limits.maxQueriesPerDay} onChange={num('maxQueriesPerDay')} /></label>
        <label>Max rows per query<input type="number" min={1} value={limits.maxRows} onChange={num('maxRows')} /></label>
        <label>Query timeout (seconds)<input type="number" min={1} max={600} value={limits.queryTimeoutSeconds} onChange={num('queryTimeoutSeconds')} /></label>
        <label>Warn when data older than (minutes)<input type="number" min={1} value={limits.staleAfterMinutes} onChange={num('staleAfterMinutes')} /></label>
        <button className="primary">Save limits</button>
      </form>}
    </Panel>
    <Panel title="Audit trail" caption="Source changes, approvals, queries, exports, sharing, and denied access. Row data is never stored here.">
      <Notice error={audit.error} />
      <div className="gv-table-wrap"><table><thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Resource</th><th>Details</th></tr></thead><tbody>
        {audit.data?.map(a => <tr key={a.id}><td>{when(a.createdAt)}</td><td>{a.actor}</td><td><span className={`gv-badge ${a.action.includes('DENIED') || a.action.includes('FAILED') ? 'warn' : 'info'}`}>{a.action}</span></td><td>{a.resourceType}</td><td>{a.details}</td></tr>)}
      </tbody></table></div>
    </Panel>
  </div>;
}

/** Per-source operations: connection test, pause, refresh, CSV upload with preview, and run history. */
export function SourceActions({ source, onChanged }: { source: DataSource & { hasSecret?: boolean; lastRunStatus?: string | null; lastError?: string | null; lastSuccessAt?: string | null; nextRunAt?: string | null; stale?: boolean; lastTestMessage?: string | null }; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [feedback, setFeedback] = useState<{ error?: string; ok?: string }>({});
  const [runs, setRuns] = useState<RunView[] | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<CsvPreview | null>(null);
  const [table, setTable] = useState('');
  const [mode, setMode] = useState<'REPLACE' | 'APPEND'>('REPLACE');
  const canUpload = source.type === 'CSV_UPLOAD' || source.type === 'FILE_DUMP';
  const canPull = source.type === 'REST_API' || source.type === 'FILE_DUMP';
  const paused = source.status === 'PAUSED';
  const act = async (okText: (r: never) => string, fn: () => Promise<unknown>) => {
    try { const r = await fn(); setFeedback({ ok: okText(r as never) }); onChanged(); } catch (e) { setFeedback({ error: message(e) }); }
  };
  const choose = async (picked: File | null) => {
    setFile(picked); setPreview(null);
    if (!picked) return;
    setTable(current => current || picked.name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '_'));
    try { setPreview(await governed.previewCsv(picked)); setFeedback({}); } catch (e) { setFeedback({ error: message(e) }); }
  };
  const runSummary = (r: RunView) => `${r.status}${r.status === 'SUCCESS' ? `: ${r.rowsLoaded} row(s) loaded${r.rowsRejected ? `, ${r.rowsRejected} rejected` : ''}` : r.message ? ` — ${r.message}` : ''}`;
  return <div className="gv-source">
    {(source.lastError || source.stale) && <p className="gv-warning"><TriangleAlert size={12} /> {source.lastError ? `Last refresh failed: ${source.lastError}. Prior data is kept.` : 'Data may be stale.'}</p>}
    <small>{source.lastSuccessAt ? `Last success ${when(source.lastSuccessAt)}` : 'No successful load yet'}{source.nextRunAt ? ` · next ${when(source.nextRunAt)}` : ''}{source.hasSecret ? ' · credential stored (hidden)' : ''}</small>
    <div className="gv-actions">
      <button className="secondary" onClick={() => void act((r: { ok: boolean; message: string }) => `${r.ok ? 'OK' : 'Failed'}: ${r.message}`, () => governed.testSource(source.id))}>Test</button>
      <button className="secondary" onClick={() => void act(() => paused ? 'Resumed.' : 'Paused.', () => paused ? governed.resumeSource(source.id) : governed.pauseSource(source.id))}>{paused ? 'Resume' : 'Pause'}</button>
      {canPull && <button className="secondary" disabled={paused} onClick={() => void act(runSummary as never, () => governed.runSource(source.id))}>Run now</button>}
      {canUpload && <button className="secondary" onClick={() => setOpen(o => !o)}><Upload size={12} /> Upload CSV</button>}
      <button className="secondary" onClick={() => governed.sourceRuns(source.id).then(setRuns).catch(e => setFeedback({ error: message(e) }))}>History</button>
    </div>
    <Notice error={feedback.error}>{feedback.ok}</Notice>
    {open && canUpload && <div className="gv-upload">
      <input type="file" accept=".csv,text/csv" aria-label="CSV file" onChange={e => void choose(e.target.files?.[0] ?? null)} />
      {preview && <>
        <p className="gv-note">{preview.validRows} valid row(s), {preview.badRowCount} bad. Detected: {preview.columns.map(c => `${c.name} (${c.kind.toLowerCase()})`).join(', ')}</p>
        {preview.badRows.slice(0, 5).map(b => <p key={b.row} className="gv-warning">Row {b.row}: {b.reason}</p>)}
        <div className="gv-row"><input value={table} onChange={e => setTable(e.target.value)} placeholder="Table name" aria-label="Table name" />
          <select value={mode} onChange={e => setMode(e.target.value as 'REPLACE' | 'APPEND')} aria-label="Load mode"><option value="REPLACE">Replace existing</option><option value="APPEND">Append</option></select>
          <button className="primary" disabled={!file || !table.trim()} onClick={() => void act(runSummary as never, () => governed.uploadCsv(source.id, file!, table, mode))}>Import</button></div>
      </>}
    </div>}
    {runs && <ul className="gv-list">{runs.slice(0, 5).map(r => <li key={r.id}><span>{when(r.startedAt)} · {r.trigger.toLowerCase()} · {runSummary(r)}</span></li>)}{!runs.length && <li>No runs yet.</li>}</ul>}
  </div>;
}
