import { useEffect, useRef, useState } from 'react';
import { useReducedMotion } from 'motion/react';
import { ArrowDown, ArrowLeft, ArrowUp, Check, Database, Download, Eye, FileJson, LayoutTemplate, Plus, RefreshCw, Save, Trash2, Upload, WandSparkles } from 'lucide-react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { templates, type RunResult, type SavedTemplate, type SourceData, type TemplateDefinition, type TemplateSource, type TemplateWidget } from './api/templates';
import { PublishButton } from './Publishing';
import { parseTemplateUpload, serializeTemplate } from './templateLayout';
import './templates.css';
import { TemplateCanvas } from './TemplateCanvas';
import { DashboardGenerator } from './DashboardGenerator';
import { displayLabel } from './displayLabel';

const chartTypes: TemplateWidget['chart'][] = ['bar', 'column', 'line', 'area', 'pie', 'donut', 'table'];
const chartColors = ['#178f96', '#456dd4', '#ed8a72', '#9275c3', '#d9ac45'];
const fieldName = (value: string) => value.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').replace(/^./, letter => letter.toUpperCase());
const message = (error: unknown) => error instanceof Error ? error.message : 'Something went wrong. Please try again.';
const emptyDefinition = (): TemplateDefinition => ({ version: 1, name: 'Untitled report', description: '', widgets: [] });

export function ReportTemplates({ canEdit, aiFirst = false }: { canEdit: boolean; aiFirst?: boolean }) {
  const [items, setItems] = useState<SavedTemplate[]>([]);
  const [sources, setSources] = useState<TemplateSource[]>([]);
  const [saved, setSaved] = useState<SavedTemplate | null>(null);
  const [definition, setDefinition] = useState<TemplateDefinition | null>(null);
  const [result, setResult] = useState<RunResult | null>(null);
  const [busy, setBusy] = useState('Loading templates');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [generator, setGenerator] = useState(aiFirst);
  const [generated, setGenerated] = useState<{ aiGenerated: boolean; message: string } | null>(null);
  const upload = useRef<HTMLInputElement>(null);
  const revision = useRef(0);
  const editable = canEdit && (!saved || saved.canEdit);
  const dirty = !!definition && (!saved || JSON.stringify(saved.definition) !== JSON.stringify(definition));
  const load = async () => {
    setBusy('Loading templates'); setError('');
    try { const [list, available] = await Promise.all([templates.list(), templates.sources()]); setItems(list); setSources(available); }
    catch (cause) { setError(message(cause)); }
    finally { setBusy(''); }
  };
  useEffect(() => { void load(); }, []);
  const change = (next: TemplateDefinition) => { revision.current++; setDefinition(next); setResult(null); setNotice(''); setError(''); };
  const leave = () => {
    if (dirty && !window.confirm('Discard unsaved template changes?')) return;
    revision.current++; setDefinition(null); setSaved(null); setResult(null); setError(''); setNotice(''); setConfirmDelete(false); setGenerated(null);
  };
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  const open = async (item: SavedTemplate) => {
    setBusy('Opening report'); setError(''); setNotice(''); setConfirmDelete(false); setGenerated(null);
    try { const current = await templates.get(item.id); setSaved(current); setDefinition(current.definition); setResult(null); revision.current++; setResult(await templates.run(item.id)); }
    catch (cause) { setError(message(cause)); }
    finally { setBusy(''); }
  };
  const preview = async () => {
    if (!definition) return;
    const current = revision.current;
    setBusy('Refreshing report data'); setError(''); setResult(null);
    try { const next = saved && !dirty ? await templates.run(saved.id) : await templates.preview(definition); if (revision.current === current) { setResult(next); setNotice('Preview refreshed from the selected data sources.'); } }
    catch (cause) { if (revision.current === current) setError(message(cause)); }
    finally { setBusy(''); }
  };
  const save = async () => {
    if (!definition) return;
    setBusy('Saving template'); setError('');
    try {
      const next = saved ? await templates.update(saved.id, definition) : await templates.create(definition);
      setSaved(next); setDefinition(next.definition); setResult(null); revision.current++;
      setItems(previous => [next, ...previous.filter(item => item.id !== next.id)]); setNotice('Template saved. Refresh the preview to see the latest data.');
    } catch (cause) { setError(message(cause)); }
    finally { setBusy(''); }
  };
  const importFile = async (file: File) => {
    setBusy('Validating uploaded template'); setError(''); setNotice('');
    try {
      if (file.size > 65536) throw new Error('Template files must be 64 KB or smaller.');
      const next = await templates.preview(parseTemplateUpload(await file.text()));
      revision.current++; setSaved(null); setDefinition(next.definition); setResult(next); setGenerated(null); setNotice('Template validated. Review the bindings and save it to your workspace.');
    } catch (cause) { setError(message(cause)); }
    finally { setBusy(''); if (upload.current) upload.current.value = ''; }
  };
  const download = () => {
    if (!definition) return;
    const url = URL.createObjectURL(new Blob([serializeTemplate(definition)], { type: 'application/json' }));
    const link = document.createElement('a'); link.href = url; link.download = `${definition.name.replace(/[^a-z0-9-]+/gi, '-').slice(0, 80) || 'report'}-template.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000); setNotice('Layout downloaded. Report data and credentials are excluded.');
  };
  const remove = async () => {
    if (!saved) return;
    setBusy('Deleting template'); setError('');
    try { await templates.remove(saved.id); setItems(previous => previous.filter(item => item.id !== saved.id)); setDefinition(null); setSaved(null); setResult(null); setConfirmDelete(false); setGenerated(null); setNotice('Template deleted.'); }
    catch (cause) { setError(message(cause)); }
    finally { setBusy(''); }
  };
  const updateWidget = (index: number, widget: TemplateWidget) => { if (definition) change({ ...definition, widgets: definition.widgets.map((item, i) => i === index ? widget : item) }); };
  const move = (index: number, direction: number) => {
    if (!definition) return;
    const widgets = [...definition.widgets]; [widgets[index], widgets[index + direction]] = [widgets[index + direction], widgets[index]]; change({ ...definition, widgets });
  };
  return <section className="template-studio">
    {!definition && generator ? <DashboardGenerator canEdit={canEdit} onBack={() => setGenerator(false)} onGenerated={(next, origin) => {
      if (!canEdit) return;
      revision.current++; setSaved(null); setDefinition(next.definition); setResult(next); setGenerated(origin); setGenerator(false); setError(''); setNotice(''); setConfirmDelete(false);
    }} /> : !definition ? <>
      <header className="template-hero"><div><span className="template-eyebrow"><LayoutTemplate size={16} /> YOUR DATA. YOUR DESIGN.</span><h1>A report that fits<br />the way you work.</h1><p>Drag charts and tables onto your canvas, connect your data, and publish your report. No file upload needed.</p><div className="template-hero-actions">{canEdit && <><button className="primary" disabled={!!busy} onClick={() => { setGenerator(true); setGenerated(null); setError(''); setNotice(''); }}><WandSparkles size={17} /> Create with AI</button><button className="secondary" disabled={!!busy} onClick={() => { setSaved(null); setGenerated(null); change(emptyDefinition()); }}><Plus size={17} /> Create template</button><button className="secondary" disabled={!!busy} onClick={() => upload.current?.click()}><Upload size={16} /> Upload JSON</button></>}<a href="/templates/operations-report.json" download><Download size={15} /> Download example</a></div></div><div className="template-hero-art" aria-hidden="true"><div><i /><i /><i /></div><span><b /><b /><b /><b /><b /></span><small><i /><i /><i /></small></div></header>
      <input ref={upload} className="sr-only" type="file" accept=".json,application/json" aria-label="Upload report template JSON" onChange={event => { const file = event.target.files?.[0]; if (file) void importFile(file); }} />
      <div className="template-section-heading"><div><h2>Your template library <span>{items.length}</span></h2><p>Layouts stay reusable. Data is fetched when you run a report.</p></div><button className="secondary" disabled={!!busy} onClick={() => void load()}><RefreshCw size={15} /> Refresh</button></div>
      <div className="template-library">{items.map((item, index) => <article className="template-library-card" key={item.id}><div className={`template-card-art tone-${index % 3}`} aria-hidden="true"><LayoutTemplate size={38} /><span>{item.definition.widgets.length.toString().padStart(2, '0')}</span></div><div><span className="template-eyebrow">SAVED LAYOUT · {item.definition.widgets.length} WIDGETS</span><h3>{item.definition.name}</h3><p>{item.definition.description || 'A reusable report connected to workspace data.'}</p><small>By {item.ownerName} · Updated {new Date(item.updatedAt).toLocaleDateString()}</small><button className="secondary" disabled={!!busy} onClick={() => void open(item)}><Eye size={15} /> Open template</button></div></article>)}</div>
      {!busy && !error && !items.length && <div className="template-empty"><FileJson size={30} /><h3>Your next report starts here.</h3><p>Choose Create template to build a report with drag and drop.</p></div>}
    </> : <>
      <button className="secondary template-back" disabled={!!busy} onClick={leave}><ArrowLeft size={16} /> Template library</button>
      <header className="template-editor-heading"><div><span className="template-eyebrow">REPORT TEMPLATE / {dirty ? 'UNSAVED CHANGES' : 'SAVED'}</span><h1>{definition.name || 'Untitled report'}</h1><p>Drag charts and tables onto the canvas. Configure their data, preview, then save and publish.</p></div><div className="template-actions"><button className="secondary" onClick={download} disabled={!!busy}><Download size={15} /> Download JSON</button>{editable && <button className="primary" disabled={!!busy || !dirty || !definition.widgets.length} onClick={() => void save()}><Save size={16} /> Save template</button>}{saved && !dirty && canEdit && !busy && <PublishButton definition={{ name: saved.definition.name, kind: 'template', reportId: saved.id }} />}</div></header>
      {generated && <div className="generator-draft-notice" role="status"><WandSparkles size={17} /><div><strong>{generated.aiGenerated ? dirty ? 'AI draft' : 'AI-assisted layout' : 'Starter layout'}</strong><p>{displayLabel(generated.message)} Review the fields and data before publishing.</p></div></div>}<div className="template-editor-grid"><fieldset className="template-settings" disabled={!!busy || !editable}><legend className="sr-only">Template settings</legend><div className="template-settings-heading"><LayoutTemplate size={18} /><h2>Layout & bindings</h2><span>{definition.widgets.length}/12</span></div><label>Report name<input maxLength={120} value={definition.name} onChange={event => change({ ...definition, name: event.target.value })} /></label><label>Description<textarea maxLength={1000} rows={3} value={definition.description} onChange={event => change({ ...definition, description: event.target.value })} placeholder="What does this report help you understand?" /></label>
        <div className="template-widget-editors">{definition.widgets.map((widget, index) => <WidgetEditor key={widget.id} widget={widget} sources={sources} index={index} count={definition.widgets.length} onChange={next => updateWidget(index, next)} onMove={direction => move(index, direction)} onRemove={() => change({ ...definition, widgets: definition.widgets.filter((_, i) => i !== index) })} />)}</div>
        <button className="template-add" disabled={definition.widgets.length >= 12} onClick={() => change({ ...definition, widgets: [...definition.widgets, { id: crypto.randomUUID(), title: `Widget ${definition.widgets.length + 1}`, sourceType: 'workbook', sourceId: '', chart: 'column', labelKey: '', valueKey: '', columns: [], width: 'half' }] })}><Plus size={17} /> Add widget</button>
      </fieldset><div className="template-preview"><div className="template-preview-heading"><div><span className="template-eyebrow">REPORT CANVAS</span><h2>{result ? 'Your data, in view.' : 'Ready when you are.'}</h2></div><button className="primary" disabled={!!busy || !definition.widgets.length} onClick={() => void preview()}><RefreshCw size={15} /> {result ? 'Refresh data' : 'Preview report'}</button></div>
        {editable && <TemplateCanvas widgets={definition.widgets} disabled={!!busy} onChange={widgets => change({ ...definition, widgets })} onConfigure={id => {
          const editor = document.getElementById(`widget-settings-${id}`) as HTMLDetailsElement | null;
          if (editor) { editor.open = true; editor.scrollIntoView({ block: 'center' }); editor.querySelector('input')?.focus({ preventScroll: true }); }
        }} />}
        {result ? <TemplateReportView result={result} /> : <p className="template-preview-hint">Choose the data fields for your widgets, then select Preview report to load the latest database results.</p>}
      </div></div>
      {saved && editable && <div className="template-delete">{confirmDelete ? <><p>Delete this template? Published snapshots remain available until revoked.</p><button className="secondary" disabled={!!busy} onClick={() => void remove()}>Confirm delete</button><button className="secondary" disabled={!!busy} onClick={() => setConfirmDelete(false)}>Keep template</button></> : <button className="secondary" disabled={!!busy} onClick={() => setConfirmDelete(true)}><Trash2 size={14} /> Delete template</button>}</div>}
    </>}
    <div className="template-feedback" aria-live="polite">{busy && <p role="status"><RefreshCw size={15} /> {busy}…</p>}{error && <p className="template-error" role="alert">{error}</p>}{notice && !busy && <p role="status"><Check size={15} /> {notice}</p>}</div>
  </section>;
}

function WidgetEditor({ widget, sources, index, count, onChange, onMove, onRemove }: { widget: TemplateWidget; sources: TemplateSource[]; index: number; count: number; onChange: (widget: TemplateWidget) => void; onMove: (direction: number) => void; onRemove: () => void }) {
  const [data, setData] = useState<SourceData | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let active = true; setData(null); setError('');
    if (!widget.sourceId) { setLoading(false); return; }
    setLoading(true);
    void templates.source(widget.sourceType, widget.sourceId).then(result => { if (active) setData(result); }).catch(cause => { if (active) setError(message(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [widget.sourceType, widget.sourceId]);
  const columns = data?.columns ?? [];
  const numeric = columns.filter(column => !data?.rows.length || data.rows.some(row => typeof row[column] === 'number'));
  return <details id={`widget-settings-${widget.id}`} className="template-widget-editor" open><summary><span><b>{String(index + 1).padStart(2, '0')}</b>{widget.title || 'Untitled widget'}</span><span>{fieldName(widget.chart)}</span></summary><div className="template-widget-fields"><div className="template-widget-tools"><button type="button" aria-label={`Move widget ${index + 1} up`} disabled={index === 0} onClick={() => onMove(-1)}><ArrowUp size={15} /></button><button type="button" aria-label={`Move widget ${index + 1} down`} disabled={index + 1 === count} onClick={() => onMove(1)}><ArrowDown size={15} /></button><button type="button" aria-label={`Remove widget ${index + 1}`} onClick={onRemove}><Trash2 size={15} /></button></div>
    <label>Widget title<input maxLength={120} value={widget.title} onChange={event => onChange({ ...widget, title: event.target.value })} /></label>
    <label>Data source<select aria-label="Data source" value={widget.sourceId ? `${widget.sourceType}:${widget.sourceId}` : ''} onChange={event => { const source = sources.find(item => `${item.type}:${item.id}` === event.target.value); if (source) onChange({ ...widget, sourceType: source.type, sourceId: source.id, labelKey: '', valueKey: '', columns: [] }); }}><option value="" disabled>Choose a source</option>{sources.map(source => <option key={`${source.type}:${source.id}`} value={`${source.type}:${source.id}`}>{source.name} · {source.sample ? 'Sample' : 'Live'}</option>)}</select></label>
    <div className="template-field-pair"><label>Visualization<select aria-label="Visualization" value={widget.chart} onChange={event => onChange({ ...widget, chart: event.target.value as TemplateWidget['chart'] })}>{chartTypes.map(chart => <option key={chart} value={chart}>{fieldName(chart)}</option>)}</select></label><label>Width<select aria-label="Width" value={widget.width} onChange={event => onChange({ ...widget, width: event.target.value as TemplateWidget['width'] })}><option value="half">Half width</option><option value="full">Full width</option></select></label></div>
    {loading && <p role="status">Loading available fields…</p>}{error && <p className="template-error" role="alert">{error}</p>}
    {data && <>{widget.chart === 'table' ? <fieldset className="template-column-picker"><legend>Table columns</legend>{columns.map(column => <label key={column}><input type="checkbox" checked={widget.columns.includes(column)} onChange={event => onChange({ ...widget, columns: event.target.checked ? [...widget.columns, column] : widget.columns.filter(key => key !== column) })} />{fieldName(column)}</label>)}</fieldset> : <div className="template-field-pair"><label>Label field<select aria-label="Label field" value={widget.labelKey} onChange={event => onChange({ ...widget, labelKey: event.target.value })}><option value="">Choose field</option>{columns.map(column => <option key={column} value={column}>{fieldName(column)}</option>)}</select></label><label>Value field<select aria-label="Value field" value={widget.valueKey} onChange={event => onChange({ ...widget, valueKey: event.target.value })}><option value="">Choose numeric field</option>{numeric.map(column => <option key={column} value={column}>{fieldName(column)}</option>)}</select></label></div>}<small className="template-source-note"><Database size={13} /> {data.sample ? 'Sample demonstration data' : 'Live workspace data'} · {data.rows.length} rows</small>{data.warnings.map((warning, i) => <p className="template-warning" key={i}>{displayLabel(warning)}</p>)}</>}
  </div></details>;
}

export function TemplateReportView({ result }: { result: RunResult }) {
  return <div className="template-report"><header><h2>{result.definition.name}</h2>{result.definition.description && <p>{result.definition.description}</p>}</header><div className="template-report-grid">{result.widgets.map(item => <article className={`template-result-widget ${item.widget.width}`} key={item.widget.id}><header><div><span className={item.sample ? 'template-data-badge sample' : 'template-data-badge'}><Database size={12} /> {item.sample ? 'Sample data' : 'Workspace data'}</span><h3>{item.widget.title}</h3></div><span>{item.rows.length} rows</span></header>{item.warnings.map((warning, index) => <p className="template-warning" key={index}>{displayLabel(warning)}</p>)}{item.rows.length ? <TemplateChart item={item} /> : <p className="template-no-rows">This source returned no rows.</p>}<footer>Data retrieved {new Date(item.executedAt).toLocaleString()}</footer></article>)}</div></div>;
}

function TemplateChart({ item }: { item: RunResult['widgets'][number] }) {
  const reduced = useReducedMotion();
  const { widget, rows } = item;
  if (widget.chart === 'table') return <div className="report-table-scroll"><table><caption className="sr-only">{widget.title}</caption><thead><tr>{widget.columns.map(column => <th key={column}>{fieldName(column)}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{widget.columns.map(column => <td key={column}>{typeof row[column] === 'number' ? new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(row[column] as number) : String(row[column] ?? '—')}</td>)}</tr>)}</tbody></table></div>;
  const tooltip = <Tooltip contentStyle={{ background: 'var(--surface)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 10, fontSize: 12 }} />;
  const labelValue = (row: Record<string, unknown>) => row[widget.labelKey] as string | number;
  const numericValue = (row: Record<string, unknown>) => row[widget.valueKey] as number;
  const axes = <><CartesianGrid vertical={false} stroke="var(--line)" strokeDasharray="3 5" /><XAxis dataKey={labelValue} name={fieldName(widget.labelKey)} tick={{ fontSize: 10, fill: 'var(--muted)' }} tickLine={false} axisLine={false} /><YAxis tick={{ fontSize: 10, fill: 'var(--muted)' }} tickLine={false} axisLine={false} width={48} /></>;
  return <div className="template-chart" role="img" aria-label={`${widget.title}: ${fieldName(widget.valueKey)} by ${fieldName(widget.labelKey)}`}><ResponsiveContainer width="100%" height="100%">
    {widget.chart === 'pie' || widget.chart === 'donut' ? <PieChart><Pie data={rows} dataKey={numericValue} nameKey={labelValue} innerRadius={widget.chart === 'donut' ? '50%' : 0} outerRadius="80%" paddingAngle={2} isAnimationActive={!reduced}>{rows.map((_, index) => <Cell key={index} fill={chartColors[index % chartColors.length]} />)}</Pie>{tooltip}</PieChart>
      : widget.chart === 'line' ? <LineChart data={rows} margin={{ top: 15, right: 15, bottom: 10 }}>{axes}{tooltip}<Line dataKey={numericValue} name={fieldName(widget.valueKey)} type="monotone" stroke={chartColors[0]} strokeWidth={3} dot={rows.length < 20} isAnimationActive={!reduced} /></LineChart>
      : widget.chart === 'area' ? <AreaChart data={rows} margin={{ top: 15, right: 15, bottom: 10 }}>{axes}{tooltip}<Area dataKey={numericValue} name={fieldName(widget.valueKey)} type="monotone" stroke={chartColors[0]} fill={chartColors[0]} fillOpacity={.15} strokeWidth={3} isAnimationActive={!reduced} /></AreaChart>
      : <BarChart data={rows} layout={widget.chart === 'bar' ? 'vertical' : 'horizontal'} margin={{ top: 15, right: 15, bottom: 10 }}>{widget.chart === 'bar' ? <><CartesianGrid horizontal={false} stroke="var(--line)" strokeDasharray="3 5" /><XAxis type="number" tick={{ fontSize: 10, fill: 'var(--muted)' }} /><YAxis type="category" dataKey={labelValue} name={fieldName(widget.labelKey)} width={100} tick={{ fontSize: 10, fill: 'var(--muted)' }} /></> : axes}{tooltip}<Bar dataKey={numericValue} name={fieldName(widget.valueKey)} fill={chartColors[0]} radius={5} maxBarSize={36} isAnimationActive={!reduced} /></BarChart>}
  </ResponsiveContainer></div>;
}
