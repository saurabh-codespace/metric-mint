import { useEffect, useState, type CSSProperties } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ArrowLeft, ArrowUpRight, BarChart3, CalendarDays, Database, Download, FileSpreadsheet, Search, ShieldCheck, Sparkles } from 'lucide-react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { workbooks, type WorkbookChart, type WorkbookReport } from './api/workbooks';
import { PublishButton, PublishedList } from './Publishing';
import { reportCsv } from './reportCsv';

const colors = ['#1766df', '#078f83', '#cf6727', '#d94b65', '#1684aa'];
const label = (key: string) => key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/Usd$/, ' USD').replace(/^./, value => value.toUpperCase());
const format = (value: number, unit = '') => unit === 'USD'
  ? new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(value)
  : `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(value)}${unit === '%' ? '%' : unit ? ` ${unit}` : ''}`;
const chartTypes: WorkbookChart[] = ['bar', 'column', 'line', 'area', 'pie', 'donut', 'table'];

export function WorkbookReports({ canPublish }: { canPublish: boolean }) {
  const [reports, setReports] = useState<WorkbookReport[]>([]);
  const [selected, setSelected] = useState<WorkbookReport | null>(null);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('All reports');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'workbooks' | 'published'>('workbooks');
  const reduce = useReducedMotion();
  const load = () => { setLoading(true); setError(''); void workbooks.list().then(setReports).catch(() => setError('Could not load workbook reports. Check the backend connection and retry.')).finally(() => setLoading(false)); };
  useEffect(load, []);
  const visible = reports.filter(report => (category === 'All reports' || report.category === category) && `${report.title} ${report.description}`.toLowerCase().includes(query.toLowerCase()));
  if (selected) return <WorkbookReportView key={selected.id} report={selected} canPublish={canPublish} onBack={() => setSelected(null)} />;
  return <div className="reports-studio">
    <section className="reports-hero"><div><span className="reports-eyebrow"><Sparkles size={15} /> YOUR REPORTING COMMAND CENTER</span><h1>Good data.<br />Great decisions.</h1><p>Five perspectives on your operations, inspired by your IGP reporting workbook. Explore the numbers. Share the story.</p><span className="report-demo-note"><Database size={15} /> SQL Server sample data · 9 October 2026 snapshot</span></div><div className="reports-summary"><FileSpreadsheet size={34} /><strong>05</strong><span>workbook reports</span><small>Operations · Quality · Savings · Workforce</small></div></section>
    <div className="reports-controls"><div className="overview-mode"><button className={tab === 'workbooks' ? 'active' : ''} onClick={() => setTab('workbooks')}>Workbook reports</button><button className={tab === 'published' ? 'active' : ''} onClick={() => setTab('published')}>Published dashboards</button></div>{tab === 'workbooks' && <label className="search"><Search size={17} /><input aria-label="Search workbook reports" placeholder="Find your next insight…" value={query} onChange={event => setQuery(event.target.value)} /></label>}</div>
    {tab === 'published' ? <PublishedList /> : <><div className="report-studio-toolbar"><div className="report-category-filters" aria-label="Report categories">{['All reports', ...new Set(reports.map(report => report.category))].map(item => <button key={item} className={category === item ? 'active' : ''} aria-pressed={category === item} onClick={() => setCategory(item)}>{item}</button>)}</div><button className="secondary" disabled={loading} onClick={load}>Refresh reports</button></div>
      {loading ? <p role="status">Loading your report collection…</p> : error ? <div className="state-card" role="alert"><p>{error}</p><button className="secondary" onClick={load}>Retry</button></div> : <motion.div layout={!reduce} className="reports-gallery"><AnimatePresence>{visible.map((report, index) => <motion.article layout={!reduce} key={report.id} initial={{ opacity: 0, y: reduce ? 0 : 18 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : .3, delay: reduce ? 0 : index * .045 }} className="studio-report-card" style={{ '--report-accent': colors[reports.indexOf(report) % colors.length] } as CSSProperties}>
        <div className="studio-preview"><span className="studio-preview-label"><BarChart3 size={16} /> {report.category}</span><WorkbookChartView report={report} chart={report.chartType} compact /><span className="sample-badge">Sample data</span></div>
        <div className="studio-card-body"><div className="studio-card-meta"><span><CalendarDays size={13} /> {report.frequency}</span><span>Excel row {report.workbookRow}</span></div><h2>{report.title}</h2><p>{report.description}</p><div className="studio-kpi-row">{report.kpis.slice(0, 2).map(kpi => <div className="studio-kpi" key={kpi.label}><small>{kpi.label}</small><strong>{format(kpi.value, kpi.unit)}</strong></div>)}</div><div className="studio-card-actions"><span>{report.data.length} data points</span><button className="primary" aria-label={`Open ${report.title}`} onClick={() => { setSelected(report); window.scrollTo(0, 0); }}>Explore report <ArrowUpRight size={17} /></button></div></div>
      </motion.article>)}</AnimatePresence></motion.div>}{!loading && !error && !visible.length && <div className="state-card"><Search /><h2>No matching reports</h2><button className="secondary" onClick={() => { setQuery(''); setCategory('All reports'); }}>Clear filters</button></div>}
    </>}
  </div>;
}

export function WorkbookReportView({ report, canPublish = false, initialChart, onBack }: { report: WorkbookReport; canPublish?: boolean; initialChart?: string; onBack?: () => void }) {
  const [chart, setChart] = useState<WorkbookChart>(chartTypes.includes(initialChart as WorkbookChart) ? initialChart as WorkbookChart : report.chartType);
  const [notice, setNotice] = useState('');
  const reduce = useReducedMotion();
  const exportData = () => {
    const url = URL.createObjectURL(new Blob([reportCsv(report.data)], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = `${report.id}-sample.csv`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000); setNotice('Sample report data exported.');
  };
  return <div className="report-workspace">{onBack && <button className="secondary" onClick={onBack}><ArrowLeft size={16} /> All workbook reports</button>}<header className="report-workspace-header"><div><span className="reports-eyebrow">{report.category} / {report.frequency}</span><h1>{report.title}</h1><p>{report.description}</p></div><div className="studio-card-actions"><button className="secondary" onClick={exportData}><Download size={16} /> Export CSV</button>{canPublish && <PublishButton definition={{ name: report.title, kind: 'workbook', reportId: report.id, chart }} />}</div></header>
    <div className="report-demo-note"><ShieldCheck size={16} /><span>Synthetic demonstration data · Snapshot {report.sampleAsOf} · Excel: IGP Activity Tracker Details, row {report.workbookRow}. These figures are not actual business results.</span></div>
    <section className="studio-kpi-row" aria-label="Report KPIs">{report.kpis.map((kpi, index) => <motion.article initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: reduce ? 0 : .3, delay: reduce ? 0 : index * .05 }} className="studio-kpi" key={kpi.label}><small>{kpi.label}</small><strong>{format(kpi.value, kpi.unit)}</strong></motion.article>)}</section>
    <section className="report-chart-panel"><div className="report-studio-toolbar"><div><h2>{label(report.valueKey)} by {label(report.labelKey).toLowerCase()}</h2><p>Hover over the chart to inspect each data point.</p></div><label>Visualization<select aria-label="Report visualization" value={chart} onChange={event => setChart(event.target.value as WorkbookChart)}>{chartTypes.map(type => <option key={type} value={type}>{label(type)}</option>)}</select></label></div><WorkbookChartView report={report} chart={chart} /></section>
    {chart !== 'table' && <section className="report-data-panel"><div className="report-studio-toolbar"><div><h2>Behind the numbers</h2><p>All {report.data.length} rows used in this report. Values come from the sample database.</p></div><Database size={20} /></div><ReportTable report={report} /></section>}
    <p role="status">{notice}</p>
  </div>;
}

function ReportTable({ report }: { report: WorkbookReport }) {
  const columns = Object.keys(report.data[0] ?? {});
  return <div className="report-table-scroll"><table><caption className="sr-only">{report.title} sample data</caption><thead><tr>{columns.map(key => <th key={key}>{label(key)}</th>)}</tr></thead><tbody>{report.data.map((row, index) => <tr key={index}>{columns.map(key => <td key={key}>{typeof row[key] === 'number' ? format(row[key] as number, key.endsWith('Percent') ? '%' : key.endsWith('Usd') ? 'USD' : '') : String(row[key] ?? '—')}</td>)}</tr>)}</tbody></table></div>;
}

function WorkbookChartView({ report, chart, compact = false }: { report: WorkbookReport; chart: WorkbookChart; compact?: boolean }) {
  const reduced = useReducedMotion();
  if (chart === 'table') return <ReportTable report={report} />;
  const axis = <><CartesianGrid stroke="var(--line)" strokeDasharray="3 6" vertical={false} /><XAxis dataKey={report.labelKey} hide={compact} tick={{ fill: 'var(--muted)', fontSize: 11 }} tickFormatter={value => String(value).replace('2026-', '')} axisLine={false} tickLine={false} /><YAxis hide={compact} tick={{ fill: 'var(--muted)', fontSize: 11 }} axisLine={false} tickLine={false} /></>;
  const tooltip = <Tooltip contentStyle={{ borderRadius: 12, border: '1px solid var(--line)', background: 'var(--surface)', color: 'var(--text)' }} />;
  const chartColor = colors[['queue-management', 'on-hold', 'csat-dashboard', 'savings-collection', 'utilization'].indexOf(report.id)] ?? colors[0];
  return <div className={`workbook-chart ${compact ? 'compact' : ''}`} style={{ width: '100%', height: compact ? 150 : 330 }} role="img" aria-label={`${label(report.valueKey)} by ${report.labelKey}`}><ResponsiveContainer width="100%" height="100%">
    {chart === 'pie' || chart === 'donut' ? <PieChart><Pie data={report.data} dataKey={report.valueKey} nameKey={report.labelKey} innerRadius={chart === 'donut' ? '52%' : 0} outerRadius="82%" paddingAngle={3} isAnimationActive={!reduced}>{report.data.map((_, index) => <Cell fill={colors[index % colors.length]} key={index} />)}</Pie>{tooltip}</PieChart>
      : chart === 'line' ? <LineChart data={report.data} margin={{ top: 12, right: 15, left: compact ? 0 : 0, bottom: 10 }}>{axis}{tooltip}<Line type="monotone" dataKey={report.valueKey} name={label(report.valueKey)} stroke={chartColor} strokeWidth={3} dot={!compact} isAnimationActive={!reduced} /></LineChart>
      : chart === 'area' ? <AreaChart data={report.data} margin={{ top: 12, right: 15, bottom: 10 }}>{axis}{tooltip}<Area type="monotone" dataKey={report.valueKey} name={label(report.valueKey)} stroke={chartColor} fill={chartColor} fillOpacity={.16} strokeWidth={3} isAnimationActive={!reduced} /></AreaChart>
      : <BarChart data={report.data} layout={chart === 'bar' && !compact ? 'vertical' : 'horizontal'} margin={{ top: 12, right: 15, bottom: 10 }}>{chart === 'bar' && !compact ? <><CartesianGrid stroke="var(--line)" horizontal={false} strokeDasharray="3 6" /><XAxis type="number" tick={{ fill: 'var(--muted)', fontSize: 11 }} /><YAxis type="category" dataKey={report.labelKey} width={115} tick={{ fill: 'var(--muted)', fontSize: 11 }} /></> : axis}{tooltip}<Bar dataKey={report.valueKey} name={label(report.valueKey)} fill={chartColor} radius={5} maxBarSize={35} isAnimationActive={!reduced} /></BarChart>}
  </ResponsiveContainer></div>;
}
