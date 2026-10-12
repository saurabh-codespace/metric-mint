import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import {
  ArrowRight, ArrowUpRight, Bell, CheckCircle2,
  CircleHelp, Database, Eye, EyeOff, FileBarChart, Gauge, LayoutDashboard, LockKeyhole, LogIn, LogOut, Menu,
  MessageSquareText, Moon, Plus, RefreshCw, Search, Settings, SquarePen, Trash2,
  ShieldCheck, Sun, Table2, Target, WandSparkles, X, Check, Info, SlidersHorizontal,
} from 'lucide-react';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Line, LineChart,
  Pie, PieChart, Radar, RadarChart, PolarAngleAxis, PolarGrid, PolarRadiusAxis,
  ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Explore, Governance, SourceActions } from './Governed';
import { Dashboard } from './Dashboard';
import { WorkbookReports } from './WorkbookReports';
import { ReportTemplates } from './ReportTemplates';
import { PublishedDashboard } from './PublishedDashboard';
import { ChatView, useChatThreads } from './Chat';
import { ChatProjects } from './ChatProjects';
import { displayLabel } from './displayLabel';
import { api, ApiError, clearCredentials, hasCredentials, type AuthUser, type ChartType, type CreateDataSource, type DataSource, type DataSourceType, type ReportDatum, type ToscanaOverview, type ToscanaReport } from './api/client';
import { governed, type WorkspaceView } from './api/governed';

type Page = 'overview' | 'ask' | 'ai-dashboards' | 'explore' | 'sources' | 'metrics' | 'governance' | 'reports' | 'settings' | 'help';

const nav: { id: Page; label: string; icon: typeof Gauge }[] = [
  { id: 'ai-dashboards', label: 'AI dashboards', icon: WandSparkles },
  { id: 'overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'explore', label: 'Explore', icon: Table2 },
  { id: 'sources', label: 'Data sources', icon: Database },
  { id: 'metrics', label: 'Metrics & KPIs', icon: Target },
  { id: 'reports', label: 'Reports', icon: FileBarChart },
  { id: 'governance', label: 'Governance', icon: ShieldCheck },
];

function useRemote<T>(loader: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const refresh = useCallback(async () => {
    setLoading(true); setError('');
    try { setData(await loader()); }
    catch (caught) {
      setError(caught instanceof ApiError && caught.status === 404
        ? 'This Metric Mint API is not available in the running backend. Restart the latest Spring Boot service and retry.'
        : 'Could not load live data. Check the backend connection and try again.');
    } finally { setLoading(false); }
  }, [loader]);
  useEffect(() => { void refresh(); }, [refresh]);
  return { data, loading, error, refresh };
}

function LoginPage({ onSignedIn }: { onSignedIn: (user: AuthUser) => void }) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('admin');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError('');
    try { onSignedIn(await api.login(username.trim(), password)); }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Could not sign in.'); }
    finally { setBusy(false); }
  };
  return <main className="login-shell">
    <section className="login-story">
      <div className="login-brand"><span className="brand-mark"><img src="/metric-mint-mark.svg" alt="" width="35" height="35" /></span><strong>Metric Mint</strong></div>
      <div className="login-story-copy"><span className="eyebrow">Intelligence for Invoice-to-Pay</span><h1>See the story behind every invoice.</h1><p>Turn your business data into live dashboards, trusted reports, and AI-assisted decisions.</p><div className="login-proof"><span><FileBarChart />Live operational dashboards</span><span><WandSparkles />AI-generated insights</span><span><ShieldCheck />Governed, read-only access</span></div></div>
      <small>Metric Mint · Secure analytics workspace</small>
    </section>
    <section className="login-panel"><form className="login-card" onSubmit={event => void submit(event)}>
      <span className="login-icon"><LockKeyhole /></span><div><span className="eyebrow">Welcome back</span><h2>Sign in to your workspace</h2><p>Use your administrator account to continue.</p></div>
      <label><span id="username-label">Username</span><div className="login-input"><span aria-hidden="true">AD</span><input required autoFocus aria-labelledby="username-label" autoComplete="username" value={username} onChange={event => setUsername(event.target.value)} /></div></label>
      <label><span id="password-label">Password</span><div className="login-input"><LockKeyhole size={17} /><input required aria-labelledby="password-label" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} /><button type="button" onClick={() => setShowPassword(value => !value)} aria-label={showPassword ? 'Hide password' : 'Show password'}>{showPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></div></label>
      {error && <p className="login-error" role="alert">{error}</p>}
      <button className="primary login-submit" disabled={busy}><LogIn size={17} />{busy ? 'Signing in…' : 'Sign in'}</button>
      <div className="login-demo"><span>Demo administrator</span><code>admin</code><span>/</span><code>admin</code></div>
    </form></section>
  </main>;
}

export default function App() {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [checkingAuth, setCheckingAuth] = useState(hasCredentials());
  const [page, setPage] = useState<Page>('ask');
  const [publishedToken, setPublishedToken] = useState(() => window.location.hash.startsWith('#/published/') ? window.location.hash.slice(12) : '');
  useEffect(() => {
    const sync = () => setPublishedToken(window.location.hash.startsWith('#/published/') ? window.location.hash.slice(12) : '');
    window.addEventListener('hashchange', sync);
    return () => window.removeEventListener('hashchange', sync);
  }, []);
  const [dataView, setDataView] = useState<'executive' | 'source'>('executive');
  const [menuOpen, setMenuOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [dark, setDark] = useState(false);
  useEffect(() => { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; }, [dark]);
  useEffect(() => {
    if (!hasCredentials()) return;
    void api.me().then(setUser).catch(() => clearCredentials()).finally(() => setCheckingAuth(false));
  }, []);
  useEffect(() => {
    const signedOut = () => setUser(null);
    window.addEventListener('toscana:unauthorized', signedOut);
    return () => window.removeEventListener('toscana:unauthorized', signedOut);
  }, []);
  useEffect(() => {
    const openSearch = (event: KeyboardEvent) => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setSearchOpen(true); } };
    window.addEventListener('keydown', openSearch);
    return () => window.removeEventListener('keydown', openSearch);
  }, []);
  const chat = useChatThreads(user ? `${user.workspaceId}:${user.username}` : undefined);
  const signOut = () => { clearCredentials(); setUser(null); setPage('ask'); setMenuOpen(false); };
  const canPublish = user?.roles.some(role => ['OWNER', 'ADMIN', 'ANALYST'].includes(role.replace('ROLE_', ''))) ?? false;
  const initials = user?.displayName.split(/\s+/).map(part => part[0]).join('').slice(0, 2).toUpperCase() || 'TA';
  const navigate = (next: Page) => { setPublishedToken(''); window.history.replaceState(null, '', window.location.pathname + window.location.search); setPage(next); setMenuOpen(false); window.scrollTo(0, 0); };
  const titles: Record<Page, [string, string]> = {
    'ai-dashboards': ['AI dashboards', 'Describe a dashboard, review its data, and make it yours.'],
    overview: ['Workspace / Overview', 'Your financial operations, thoughtfully connected.'],
    ask: ['Metric Mint', 'Your workspace assistant'],
    explore: ['Explore governed metrics', 'Ask in plain language or pick a metric; every answer shows its definition, filters, freshness, and SQL.'],
    governance: ['Governance', 'Approve metrics, curate the catalogue, control access, and watch usage and audit.'],
    sources: ['Data sources', 'Connect, test, refresh, and monitor the systems your data comes from.'],
    metrics: ['Invoice metrics', 'Track invoice completion, ageing, workload, and line-of-business performance.'],
    reports: ['Report workbooks', 'Open source-backed Invoice-to-Pay analysis templates.'],
    settings: ['Workspace settings', 'Manage query limits, freshness, and appearance.'],
    help: ['Help center', 'Learn the fastest path from connected data to trusted decisions.'],
  };

  if (checkingAuth) return <div className="auth-check"><span className="spinner" /><strong>Opening Metric Mint…</strong></div>;
  if (!user) return <LoginPage onSignedIn={setUser} />;
  return <div className={`app-shell chat-app ${page === 'ask' && !publishedToken ? 'chat-mode' : ''}`}>
    {menuOpen && <button className="mobile-scrim" aria-label="Close navigation" onClick={() => setMenuOpen(false)} />}
    <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
      <div className="brand"><span className="brand-mark"><img src="/metric-mint-mark.svg" alt="" width="30" height="30" /></span><span>Metric Mint</span><button className="mobile-close" onClick={() => setMenuOpen(false)} aria-label="Close navigation"><X /></button></div>
      <button className="chat-new-button" disabled={chat.creating || chat.historyLoading} onClick={() => { chat.newChat(); navigate('ask'); }}><SquarePen size={18} /><span>New chat</span></button>
      <ChatProjects chat={chat} active={page === 'ask' && !publishedToken} onNavigate={() => navigate('ask')} />
      <nav className="nav-list" aria-label="Main navigation">
        <span className="nav-label">Workspace</span>
        {nav.map(item => <button key={item.id} className={page === item.id ? 'active' : ''} onClick={() => navigate(item.id)}><item.icon size={18} /><span>{item.label}</span></button>)}
        <span className="nav-label nav-space">Manage</span>
        <button className={page === 'settings' ? 'active' : ''} onClick={() => navigate('settings')}><Settings size={18} /><span>Settings</span></button>
        <button className={page === 'help' ? 'active' : ''} onClick={() => navigate('help')}><CircleHelp size={18} /><span>Help center</span></button>
      </nav>
      <div className="user-card"><span className="avatar">{initials}</span><span><strong>{user.displayName}</strong><small>{user.roles.join(' · ').toLowerCase()}</small></span><button onClick={signOut} aria-label="Sign out" title="Sign out"><LogOut size={17} /></button></div>
    </aside>
    <main className="main">
      <header className="topbar">
        <button className="menu-button" onClick={() => setMenuOpen(true)} aria-label="Open navigation"><Menu /></button>
        <div className="topbar-page-title"><strong>{titles[page][0]}</strong><span>{titles[page][1]}</span></div>
        <button className="global-search-button" onClick={() => setSearchOpen(true)} aria-label="Search workspace"><Search size={16} /><span>Search anything...</span><kbd>Ctrl K</kbd></button><div className="top-actions"><button onClick={() => setDark(value => !value)} aria-label="Toggle theme">{dark ? <Sun size={18} /> : <Moon size={18} />}</button><NotificationCenter /><button className="top-avatar" onClick={signOut} title="Sign out">{initials}</button></div>
      </header>
      <div className="content">
        {!publishedToken && (page === 'overview' || page === 'metrics') && <div className="overview-mode"><button className={dataView === 'executive' ? 'active' : ''} onClick={() => setDataView('executive')}>Executive analytics</button><button className={dataView === 'source' ? 'active' : ''} onClick={() => setDataView('source')}>Connected data</button></div>}
        {publishedToken ? <PublishedDashboard key={`${user.username}:${publishedToken}`} token={publishedToken} /> : <>{page === 'overview' && (dataView === 'executive' ? <Dashboard canPublish={canPublish} /> : <Overview />)}{page === 'ask' && <ChatView key={chat.activeId ?? 'new:' + (chat.selectedProjectId ?? 'general')} thread={chat.activeThread} onSend={chat.send} onStop={chat.stop} busy={chat.busy} projectName={chat.selectedProjectName} creating={chat.creating} historyLoading={chat.historyLoading} onReload={chat.reloadActive} error={chat.historyError} />}{page === 'explore' && <Explore />}{page === 'governance' && <Governance />}{page === 'sources' && <DataSources />}{page === 'metrics' && (dataView === 'executive' ? <Dashboard metricsOnly canPublish={canPublish} /> : <Metrics onNavigate={navigate} />)}{page === 'ai-dashboards' && <ReportTemplates canEdit={canPublish} aiFirst />}{page === 'reports' && <Reports onNavigate={navigate} canPublish={canPublish} />}{page === 'settings' && <SettingsPage dark={dark} setDark={setDark} />}{page === 'help' && <HelpCenter onNavigate={navigate} />}</>}
      </div>
    </main>
    {searchOpen && <GlobalSearch onNavigate={next => { setSearchOpen(false); navigate(next); }} onClose={() => setSearchOpen(false)} />}
  </div>;
}

const searchablePages: Array<{ page: Page; title: string; description: string }> = [
  { page: 'ai-dashboards', title: 'AI dashboards', description: 'Generate an editable dashboard from a natural-language request' },
  { page: 'overview', title: 'Overview', description: 'Open the invoice performance dashboard' },
  { page: 'ask', title: 'Chat with your data', description: 'Ask questions and explore backend results' },
  { page: 'explore', title: 'Explore metrics', description: 'Build and run governed queries' },
  { page: 'sources', title: 'Data sources', description: 'Connect and monitor enterprise systems' },
  { page: 'metrics', title: 'Invoice metrics', description: 'Review completion, ageing, and workload' },
  { page: 'reports', title: 'Reports', description: 'Open large interactive report templates' },
  { page: 'governance', title: 'Governance', description: 'Create metrics, policies, and catalogue metadata' },
  { page: 'settings', title: 'Settings', description: 'Manage workspace limits and appearance' },
  { page: 'help', title: 'Help center', description: 'Read the quick-start guide and FAQs' },
];

function GlobalSearch({ onNavigate, onClose }: { onNavigate: (page: Page) => void; onClose: () => void }) {
  const [query, setQuery] = useState('');
  const results = searchablePages.filter(item => `${item.title} ${item.description}`.toLowerCase().includes(query.toLowerCase()));
  return <Modal title="Search Metric Mint" subtitle="Jump to any page or action." onClose={onClose}><div className="command-search"><label><Search size={18} /><input autoFocus value={query} onChange={event => setQuery(event.target.value)} placeholder="Search reports, metrics, AI, settings..." /></label><div>{results.map(item => <button key={item.page} onClick={() => onNavigate(item.page)}><span><strong>{item.title}</strong><small>{item.description}</small></span><ArrowRight size={16} /></button>)}{!results.length && <EmptyState text="No pages or actions match this search." />}</div></div></Modal>;
}

function NotificationCenter() {
  const [open, setOpen] = useState(false);
  const [sources, setSources] = useState<DataSource[] | null>(null);
  const [error, setError] = useState(false);
  const toggle = async () => {
    const next = !open; setOpen(next);
    if (next && !sources) { try { setSources(await api.getSources()); } catch { setError(true); } }
  };
  const alerts = sources?.filter(source => !['HEALTHY', 'ACTIVE', 'CONFIGURED'].includes(source.status?.toUpperCase())) ?? [];
  return <div className="notification-wrap"><button onClick={() => void toggle()} aria-label="Notifications" aria-expanded={open}><Bell size={19} />{alerts.length > 0 && <i />}</button>{open && <div className="notification-panel"><header><strong>Notifications</strong><span>{alerts.length || 'All clear'}</span></header>{error ? <p>Could not check source health.</p> : !sources ? <p>Checking source health...</p> : alerts.length ? alerts.map(source => <article key={source.id}><Info size={16} /><span><strong>{displayLabel(source.name)}</strong><small>Status: {source.status}</small></span></article>) : <article><CheckCircle2 size={17} /><span><strong>All systems operational</strong><small>Your configured sources have no active alerts.</small></span></article>}</div>}</div>;
}

const formatter = new Intl.NumberFormat('en-IN');
const formatNumber = (value: number) => formatter.format(value ?? 0);
const asPercent = (value: number) => `${Number(value ?? 0).toFixed(1)}%`;

function Overview() {
  const { data, loading, error, refresh } = useRemote(api.getOverview);
  const [refreshingCache, setRefreshingCache] = useState(false);
  const [cacheError, setCacheError] = useState('');
  const refreshCache = async () => {
    setRefreshingCache(true); setCacheError('');
    try { await api.refreshToscanaCache(); await refresh(); }
    catch { setCacheError('Cache refresh failed. Existing connected data remains available.'); }
    finally { setRefreshingCache(false); }
  };
  if (loading && !data) return <LoadingState label="Loading invoice overview..." />;
  if (error && !data) return <ErrorState message={error} onRetry={refresh} />;
  if (!data) return null;
  const stats = [
    ['Invoice work items', formatNumber(data.totalWorkItems), 'All invoice-to-pay records', 'I'],
    ['Completed invoices', formatNumber(data.completedWorkItems), asPercent(data.completionRate), 'C'],
    ['Open invoice workload', formatNumber(data.openWorkItems), 'Needs action', 'O'],
    ['Active LOBs', formatNumber(data.activeLobs), 'Connected data source', 'L'],
  ];
  return <><RefreshBar refreshed={data.lastRefreshed} refreshing={refreshingCache} onRefresh={refreshCache} />{cacheError && <p className="inline-error" role="alert">{cacheError}</p>}<section className="kpi-grid">{stats.map(([title, value, caption, symbol]) => <article className="kpi-card" key={title}><div className="kpi-top"><span className="kpi-icon">{symbol}</span><CheckCircle2 size={18} className="verified-icon" /></div><p>{title}</p><strong>{value}</strong><small>{caption}</small></article>)}</section><section className="dashboard-grid"><Card className="revenue-card" title="Monthly invoice volume" subtitle="Invoice volume from your connected source"><VolumeChart values={data.monthlyVolume} /></Card><Card title="Invoice status distribution" subtitle={`${formatNumber(data.totalWorkItems)} total items`}><StatusBars values={data.statuses} /></Card><Card title="LOB invoice performance" subtitle="Completion and average ageing"><LobTable values={data.lobs} /></Card><Card title="Exception queues" subtitle="Current queue workload"><QueueList values={data.queues} /></Card></section></>;
}

function VolumeChart({ values }: { values: ToscanaOverview['monthlyVolume'] }) {
  if (!values.length) return <EmptyState text="No monthly volume returned." />;
  const max = Math.max(...values.map(item => item.count), 1);
  const points = values.map((item, index) => `${values.length === 1 ? 350 : index * (700 / (values.length - 1))},${205 - (item.count / max) * 165}`).join(' ');
  return <div className="chart-wrap"><div className="chart-summary"><strong>{formatNumber(values.reduce((sum, item) => sum + item.count, 0))}</strong><span className="trend up"><ArrowUpRight size={14} /> invoices</span></div><svg className="line-chart" viewBox="0 0 700 240" preserveAspectRatio="none" role="img" aria-label="Monthly Invoice-to-Pay volume">{[40, 90, 140, 190].map(y => <line key={y} x1="0" y1={y} x2="700" y2={y} className="grid-line" />)}<polyline className="volume-area" points={`0,220 ${points} 700,220`} /><polyline className="line" points={points} />{values.map((item, index) => <text key={item.period} x={values.length === 1 ? 350 : index * (700 / (values.length - 1))} y="238" className="chart-label">{item.period}</text>)}</svg></div>;
}

function StatusBars({ values }: { values: ToscanaOverview['statuses'] }) {
  const total = values.reduce((sum, item) => sum + item.count, 0) || 1;
  return <div className="bar-list">{values.length ? values.map(item => <div key={item.name}><span><b>{item.name}</b><strong>{formatNumber(item.count)}</strong></span><i><b style={{ width: `${(item.count / total) * 100}%` }} /></i></div>) : <EmptyState text="No statuses returned." />}</div>;
}

function LobTable({ values }: { values: ToscanaOverview['lobs'] }) { return <div className="live-table"><div className="live-table-head"><span>LOB</span><span>Total</span><span>Complete</span><span>Open</span><span>Ageing</span></div>{values.length ? values.map(item => <div key={item.name}><strong>{displayLabel(item.name)}</strong><span>{formatNumber(item.total)}</span><span>{formatNumber(item.completed)}</span><span>{formatNumber(item.open)}</span><span>{item.avgAgeing.toFixed(1)} days</span></div>) : <EmptyState text="No line-of-business data returned." />}</div>; }
function QueueList({ values }: { values: ToscanaOverview['queues'] }) { const max = Math.max(...values.map(item => item.count), 1); return <div className="queue-list">{values.length ? values.map((item, index) => <div key={item.name}><span className="queue-rank">{index + 1}</span><span><strong>{item.name}</strong><i><b style={{ width: `${(item.count / max) * 100}%` }} /></i></span><b>{formatNumber(item.count)}</b></div>) : <EmptyState text="No queue workload returned." />}</div>; }

function DataSources() {
  const sources = useRemote(api.getSources);
  const sourceTypes = useRemote(api.getSourceTypes);
  const [modal, setModal] = useState(false);
  return <><div className="section-toolbar"><div><strong className="section-title">Data connections</strong><small className="section-caption">{sources.data?.length ?? 0} configured source(s)</small></div><button className="primary" onClick={() => setModal(true)}><Plus size={17} /> Add source</button></div>{sources.loading && !sources.data ? <LoadingState label="Loading data sources..." /> : sources.error ? <ErrorState message={sources.error} onRetry={sources.refresh} /> : <div className="source-grid">{sources.data?.length ? sources.data.map(source => <SourceCard key={source.id} source={source} onChanged={() => void sources.refresh()} />) : <EmptyState text="No data source connected. Add SQL Server, SAP, Salesforce, Icertis, SharePoint, REST, or a file dump." />}</div>}{modal && <AddSourceModal sourceTypes={sourceTypes.data} typesError={sourceTypes.error} onRetryTypes={sourceTypes.refresh} onClose={() => setModal(false)} onCreated={() => { setModal(false); void sources.refresh(); }} />}</>;
}

function SourceCard({ source, onChanged }: { source: DataSource; onChanged: () => void }) { return <article className="source-card"><div className="source-top"><span className="source-logo red">{source.type.slice(0, 3).toUpperCase()}</span><span className={`status ${source.status?.toLowerCase() || 'healthy'}`}><i />{source.status || 'Configured'}</span></div><h3>{displayLabel(source.name)}</h3><p>{source.type}</p><div className="source-meta"><small>{source.host ? `${source.host}:${source.port}` : 'No network host'}</small><small>{source.databaseName}</small></div><div className="source-footer"><span><Database size={14} />{source.username}</span></div><SourceActions source={source} onChanged={onChanged} /></article>; }

type SourcePreset = { value: string; label: string; hostLabel: string; hostPlaceholder: string; datasetLabel: string; datasetPlaceholder: string; userLabel: string; userPlaceholder: string; port: number };
const SOURCE_PRESETS: SourcePreset[] = [
  { value: 'SQL_SERVER', label: 'SQL Server', hostLabel: 'Server host', hostPlaceholder: 'sql.company.net', datasetLabel: 'Database name', datasetPlaceholder: 'AnalyticsWarehouse', userLabel: 'Username', userPlaceholder: 'svc_analytics_reader', port: 1433 },
  { value: 'SAP_API', label: 'SAP API', hostLabel: 'SAP API host', hostPlaceholder: 'api.sap.company.net', datasetLabel: 'Site / dataset', datasetPlaceholder: 'S4HANA-Finance', userLabel: 'Client ID / user', userPlaceholder: 'analytics-api-client', port: 443 },
  { value: 'SALESFORCE_API', label: 'Salesforce API', hostLabel: 'Salesforce host', hostPlaceholder: 'company.my.salesforce.com', datasetLabel: 'Site / dataset', datasetPlaceholder: 'Invoice_Case__c', userLabel: 'Connected app user', userPlaceholder: 'analytics.integration@company.com', port: 443 },
  { value: 'ICERTIS_API', label: 'Icertis API', hostLabel: 'Icertis API host', hostPlaceholder: 'company.icertis.com', datasetLabel: 'Site / dataset', datasetPlaceholder: 'SupplierContracts', userLabel: 'Client ID / user', userPlaceholder: 'analytics-contract-client', port: 443 },
  { value: 'SHAREPOINT', label: 'SharePoint', hostLabel: 'SharePoint host', hostPlaceholder: 'company.sharepoint.com', datasetLabel: 'Site / dataset', datasetPlaceholder: 'sites/Finance/Documents', userLabel: 'Service account', userPlaceholder: 'svc-analytics@company.com', port: 443 },
  { value: 'REST_API', label: 'REST API', hostLabel: 'API host', hostPlaceholder: 'api.company.net', datasetLabel: 'Site / dataset', datasetPlaceholder: 'v1/invoices', userLabel: 'Client ID / user', userPlaceholder: 'analytics-client', port: 443 },
  { value: 'FILE_DUMP', label: 'File Dump', hostLabel: 'Storage host', hostPlaceholder: 'storage.company.net', datasetLabel: 'Dataset / folder', datasetPlaceholder: 'finance/daily-invoices', userLabel: 'Service account', userPlaceholder: 'svc_analytics_import', port: 443 },
];
const sourceKey = (value: string) => value.trim().toUpperCase().replace(/[ -]+/g, '_');
const sourceTypeValue = (item: DataSourceType) => typeof item === 'string' ? item : item.value ?? item.type ?? item.name ?? item.label ?? '';
const sourceTypeLabel = (item: DataSourceType) => typeof item === 'string' ? SOURCE_PRESETS.find(preset => preset.value === sourceKey(item))?.label ?? item : item.label ?? item.name ?? sourceTypeValue(item);

function AddSourceModal({ sourceTypes, typesError, onRetryTypes, onClose, onCreated }: { sourceTypes: DataSourceType[] | null; typesError: string; onRetryTypes: () => Promise<void>; onClose: () => void; onCreated: () => void }) {
  const options = sourceTypes?.length ? sourceTypes.map(item => ({ value: sourceTypeValue(item), label: sourceTypeLabel(item) })).filter(item => item.value) : SOURCE_PRESETS.map(({ value, label }) => ({ value, label }));
  const initialType = options[0]?.value ?? 'SQL_SERVER';
  const initialPreset = SOURCE_PRESETS.find(item => item.value === sourceKey(initialType)) ?? SOURCE_PRESETS[0];
  const [form, setForm] = useState<CreateDataSource>({ name: '', type: initialType, host: '', port: initialPreset.port, databaseName: '', username: '', credentialReference: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const preset = SOURCE_PRESETS.find(item => item.value === sourceKey(form.type)) ?? { ...SOURCE_PRESETS[5], label: sourceTypeLabel(form.type) };
  const change = (field: keyof CreateDataSource, value: string | number) => setForm(current => ({ ...current, [field]: value }));
  const changeType = (type: string) => { const next = SOURCE_PRESETS.find(item => item.value === sourceKey(type)) ?? SOURCE_PRESETS[5]; setForm(current => ({ ...current, type, host: '', port: next.port, databaseName: '', username: '' })); };
  const submit = async (event: FormEvent) => { event.preventDefault(); setSaving(true); setError(''); try { await api.createSource(form); onCreated(); } catch { setError('Could not save this source. Check every field and confirm the backend is running.'); } finally { setSaving(false); } };
  return <Modal title={`Connect ${preset.label}`} subtitle="Connect a data source using a credential reference only." onClose={onClose}><form className="source-form" onSubmit={event => void submit(event)}>{typesError && <div className="type-warning">Live source types are unavailable; showing supported data connectors.<button type="button" onClick={() => void onRetryTypes()}>Retry</button></div>}<label>Source type<select value={form.type} onChange={event => changeType(event.target.value)}>{options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>Connection name<input required maxLength={120} value={form.name} onChange={event => change('name', event.target.value)} placeholder={`${preset.label} - Analytics`} /></label><div className="form-grid"><label>{preset.hostLabel}<input required value={form.host} onChange={event => change('host', event.target.value)} placeholder={preset.hostPlaceholder} /></label><label>Port<input required type="number" min="1" max="65535" value={form.port} onChange={event => change('port', Number(event.target.value))} /></label></div><label>{preset.datasetLabel}<input required value={form.databaseName} onChange={event => change('databaseName', event.target.value)} placeholder={preset.datasetPlaceholder} /></label><label>{preset.userLabel}<input required value={form.username} onChange={event => change('username', event.target.value)} placeholder={preset.userPlaceholder} autoComplete="username" /></label><label>Credential reference<input required value={form.credentialReference} onChange={event => change('credentialReference', event.target.value)} placeholder={`vault:analytics/${sourceKey(form.type).toLowerCase()}`} /><small>Store only a vault or environment reference. Never enter a password or token here.</small></label>{error && <p className="form-error" role="alert">{error}</p>}<div className="modal-actions"><button type="button" className="secondary" onClick={onClose}>Cancel</button><button className="primary" disabled={saving}>{saving ? 'Saving...' : 'Connect source'}</button></div></form></Modal>;
}

function Metrics({ onNavigate }: { onNavigate: (page: Page) => void }) {
  const { data, loading, error, refresh } = useRemote(api.getOverview);
  const [search, setSearch] = useState('');
  if (loading && !data) return <LoadingState label="Calculating invoice metrics..." />;
  if (error && !data) return <ErrorState message={error} onRetry={refresh} />;
  if (!data) return null;
  const metrics = data.lobs.filter(item => displayLabel(item.name).toLowerCase().includes(search.toLowerCase()));
  return <><div className="section-toolbar"><label className="search compact"><Search size={16} /><input value={search} onChange={event => setSearch(event.target.value)} placeholder="Search line of business..." /></label><button className="primary" onClick={() => onNavigate('governance')}><Plus size={17} /> New metric</button></div><div className="metric-callout"><span><ShieldCheck /></span><div><strong>Metrics calculated from live Invoice-to-Pay data</strong><p>Invoice completion and ageing use values returned by the governed analytics API.</p></div></div><div className="data-card"><div className="metrics-table table-head"><span>Line of business</span><span>Definition</span><span>Total invoices</span><span>Completion</span><span>Avg. ageing</span><span /></div>{metrics.map(item => { const rate = item.total ? item.completed / item.total * 100 : 0; return <div className="metrics-table metric-row" key={item.name}><span className="metric-name"><i>{item.name.slice(0, 1).toUpperCase()}</i><span><strong>{displayLabel(item.name)}</strong><small>{formatNumber(item.open)} open invoices</small></span></span><code>completed / total</code><b>{formatNumber(item.total)}</b><em>{asPercent(rate)}</em><span>{item.avgAgeing.toFixed(1)} days</span><span /></div>; })}{!metrics.length && <EmptyState text="No invoice metrics match your search." />}</div></>;
}

function Reports({ onNavigate, canPublish }: { onNavigate: (page: Page) => void; canPublish: boolean }) {
  const [view, setView] = useState<'workbooks' | 'templates' | 'connected'>('workbooks');
  return <><div className="overview-mode"><button className={view === 'workbooks' ? 'active' : ''} onClick={() => setView('workbooks')}>Excel report collection</button><button className={view === 'templates' ? 'active' : ''} onClick={() => setView('templates')}>Report templates</button><button className={view === 'connected' ? 'active' : ''} onClick={() => setView('connected')}>Connected reports</button></div>{view === 'workbooks' ? <WorkbookReports canPublish={canPublish} /> : view === 'templates' ? <ReportTemplates canEdit={canPublish} /> : <ConnectedReports onNavigate={onNavigate} />}</>;
}

function ConnectedReports({ onNavigate }: { onNavigate: (page: Page) => void }) {
  const { data, loading, error, refresh } = useRemote(api.getToscanaReports);
  const [selected, setSelected] = useState<ToscanaReport | null>(null);
  if (loading && !data) return <LoadingState label="Loading report workbooks..." />;
  if (error && !data) return <ErrorState message={error} onRetry={refresh} />;
  return <><div className="workbook-hero"><div><span className="eyebrow">Invoice-to-Pay analytics</span><h2>Curated report workbooks</h2><p>Each template renders only data returned by its connected enterprise source.</p></div><button className="refresh-button" onClick={() => void refresh()} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh gallery</button></div><div className="report-grid workbook-grid">{data?.length ? data.map((report, index) => <ReportCard key={report.id || index} report={report} index={index} onOpen={() => setSelected(report)} onConnect={() => onNavigate('sources')} />) : <EmptyState text="No report workbooks are available." />}</div>{selected && <ReportDetail report={selected} onClose={() => setSelected(null)} />}</>;
}

const reportNeedsSource = (report: ToscanaReport) => report.status.toUpperCase() === 'SOURCE_REQUIRED';
function ReportCard({ report, index, onOpen, onConnect }: { report: ToscanaReport; index: number; onOpen: () => void; onConnect: () => void }) {
  const needsSource = reportNeedsSource(report);
  return <article className={`report-card workbook-card ${needsSource ? 'source-required' : ''}`}><div className={`report-preview preview-${index % 3}`}>{needsSource ? <div className="source-required-panel"><Database /><strong>Connect {report.sourceType}</strong><small>This workbook has no source data yet.</small></div> : <><button className="report-open-overlay" onClick={onOpen}>Open report <ArrowRight size={14} /></button><ReportVisual report={report} compact /></>}</div><div className="report-body"><span className="workbook-badge"><FileBarChart /> Workbook</span><span className={`report-status ${needsSource ? 'required' : ''}`}>{needsSource ? 'Source required' : report.status}</span><h3>{displayLabel(report.title)}</h3><p>{displayLabel(report.description)}</p><div className="workbook-meta"><span><Target />{displayLabel(report.category)}</span><span>{formatNumber(report.recordCount)} records</span></div>{needsSource ? <button className="primary workbook-action" onClick={onConnect}><Plus size={15} /> Connect source</button> : <button className="secondary workbook-action" onClick={onOpen}>Open report <ArrowRight size={14} /></button>}</div></article>;
}

function ReportDetail({ report, onClose }: { report: ToscanaReport; onClose: () => void }) {
  const [chartType, setChartType] = useState<ChartType>(report.chartType);
  const generated = report.generatedAt ? new Date(report.generatedAt).toLocaleString() : 'Not generated';
  return <Modal title={displayLabel(report.title)} subtitle={displayLabel(report.description)} onClose={onClose} wide><div className="workbook-detail"><div className="workbook-detail-meta"><span><small>Category</small><strong>{displayLabel(report.category)}</strong></span><span><small>Source</small><strong>{report.sourceType}</strong></span><span><small>Records</small><strong>{formatNumber(report.recordCount)}</strong></span><span><small>Generated</small><strong>{generated}</strong></span></div><div className="report-view-toolbar"><div><strong>Visualize this report</strong><small>Switch chart type without changing the underlying data.</small></div><ChartPicker value={chartType} onChange={setChartType} /></div><div className="report-view-canvas"><ReportVisual report={{ ...report, chartType }} /></div></div></Modal>;
}

const reportLabel = (row: ReportDatum, index: number) => String(row.label ?? row.name ?? row.category ?? row.period ?? row.status ?? `Item ${index + 1}`);
const numericValues = (row: ReportDatum) => Object.entries(row).filter(([, value]) => typeof value === 'number') as Array<[string, number]>;
const reportValue = (row: ReportDatum) => numericValues(row)[0]?.[1] ?? 0;
const chartColors = ['#2474ec', '#0eaa97', '#edaa35', '#ef735f', '#1684aa', '#d94b65', '#546e90'];
const chartChoices: Array<{ value: ChartType; label: string }> = [
  { value: 'bar', label: 'Bar' }, { value: 'column', label: 'Column' }, { value: 'line', label: 'Line' },
  { value: 'area', label: 'Area' }, { value: 'pie', label: 'Pie' }, { value: 'donut', label: 'Donut' }, { value: 'stacked-bar', label: 'Stacked' },
  { value: 'radar', label: 'Radar' }, { value: 'scatter', label: 'Scatter' }, { value: 'heatmap', label: 'Heatmap' },
  { value: 'kpi', label: 'KPI' }, { value: 'table', label: 'Table' },
];

function ChartPicker({ value, onChange }: { value: ChartType; onChange: (value: ChartType) => void }) {
  return <label className="chart-picker"><span>Chart type</span><select value={value} onChange={event => onChange(event.target.value as ChartType)}>{chartChoices.map(choice => <option key={choice.value} value={choice.value}>{choice.label}</option>)}</select></label>;
}

const chartRows = (data: ReportDatum[]) => data.slice(0, 14).map((row, index) => ({ ...row, _label: reportLabel(row, index), _value: reportValue(row) }));
const chartKeys = (data: ReportDatum[]) => Array.from(new Set(data.flatMap(row => numericValues(row).map(([key]) => key)))).slice(0, 6);
const chartTooltip = { contentStyle: { borderRadius: 10, border: '1px solid var(--line)', background: 'var(--surface)', color: 'var(--text)', fontSize: 11 } };

function ReportVisual({ report, compact = false }: { report: ToscanaReport; compact?: boolean }) {
  if (!report.data?.length) return <EmptyState text="No source records returned for this workbook." />;
  switch (report.chartType) {
    case 'column': return <ColumnReport data={report.data} compact={compact} />;
    case 'pie': return <PieReport data={report.data} donut={false} />;
    case 'donut': return <PieReport data={report.data} donut />;
    case 'line': return <TrendReport data={report.data} area={false} compact={compact} />;
    case 'area': return <TrendReport data={report.data} area compact={compact} />;
    case 'stacked-bar': return <StackedBarReport data={report.data} compact={compact} />;
    case 'radar': return <RadarReport data={report.data} />;
    case 'scatter': return <ScatterReport data={report.data} />;
    case 'heatmap': return <HeatmapReport data={report.data} />;
    case 'table': return <TableReport data={report.data} compact={compact} />;
    case 'kpi': return <KpiReport data={report.data} fallback={report.recordCount} />;
    default: return <BarReport data={report.data} compact={compact} />;
  }
}

function BarReport({ data, compact }: { data: ReportDatum[]; compact: boolean }) {
  return <div className={`recharts-report ${compact ? 'compact' : ''}`}><ResponsiveContainer width="100%" height="100%"><BarChart data={chartRows(data)} layout="vertical" margin={{ top: 8, right: 18, left: 8, bottom: 4 }}><CartesianGrid stroke="var(--line)" strokeDasharray="3 5" horizontal={false} /><XAxis type="number" hide={compact} tick={{ fill: 'var(--muted)', fontSize: 10 }} /><YAxis type="category" dataKey="_label" width={compact ? 72 : 120} tick={{ fill: 'var(--muted)', fontSize: compact ? 9 : 10 }} axisLine={false} tickLine={false} /><Tooltip {...chartTooltip} /><Bar dataKey="_value" name="Value" fill={chartColors[0]} radius={[0, 7, 7, 0]} maxBarSize={28} /></BarChart></ResponsiveContainer></div>;
}

function ColumnReport({ data, compact }: { data: ReportDatum[]; compact: boolean }) {
  return <div className={`recharts-report ${compact ? 'compact' : ''}`}><ResponsiveContainer width="100%" height="100%"><BarChart data={chartRows(data)} margin={{ top: 8, right: 8, left: compact ? -25 : 0, bottom: 4 }}><CartesianGrid stroke="var(--line)" strokeDasharray="3 5" vertical={false} /><XAxis dataKey="_label" tick={{ fill: 'var(--muted)', fontSize: 9 }} axisLine={false} tickLine={false} /><YAxis hide={compact} tick={{ fill: 'var(--muted)', fontSize: 10 }} /><Tooltip {...chartTooltip} /><Bar dataKey="_value" name="Value" fill={chartColors[1]} radius={[7, 7, 0, 0]} maxBarSize={44} /></BarChart></ResponsiveContainer></div>;
}

function PieReport({ data, donut }: { data: ReportDatum[]; donut: boolean }) {
  const rows = chartRows(data).slice(0, 8);
  return <div className="recharts-report"><ResponsiveContainer width="100%" height="100%"><PieChart><Pie data={rows} dataKey="_value" nameKey="_label" innerRadius={donut ? '48%' : 0} outerRadius="76%" paddingAngle={donut ? 3 : 1} stroke="var(--surface)" strokeWidth={3}>{rows.map((_, index) => <Cell key={index} fill={chartColors[index % chartColors.length]} />)}</Pie><Tooltip {...chartTooltip} /><Legend iconType="circle" wrapperStyle={{ fontSize: 10 }} /></PieChart></ResponsiveContainer></div>;
}

function TrendReport({ data, area, compact }: { data: ReportDatum[]; area: boolean; compact: boolean }) {
  const rows = chartRows(data); const Chart = area ? AreaChart : LineChart;
  return <div className={`recharts-report ${compact ? 'compact' : ''}`}><ResponsiveContainer width="100%" height="100%"><Chart data={rows} margin={{ top: 12, right: 12, left: compact ? -28 : 0, bottom: 4 }}><CartesianGrid stroke="var(--line)" strokeDasharray="3 5" vertical={false} /><XAxis dataKey="_label" tick={{ fill: 'var(--muted)', fontSize: 9 }} axisLine={false} tickLine={false} /><YAxis hide={compact} tick={{ fill: 'var(--muted)', fontSize: 10 }} /><Tooltip {...chartTooltip} />{area ? <Area type="monotone" dataKey="_value" name="Value" stroke={chartColors[0]} fill={chartColors[0]} fillOpacity={.16} strokeWidth={3} /> : <Line type="monotone" dataKey="_value" name="Value" stroke={chartColors[0]} strokeWidth={3} dot={{ r: 4, fill: 'var(--surface)', strokeWidth: 2 }} activeDot={{ r: 6 }} />}</Chart></ResponsiveContainer></div>;
}

function StackedBarReport({ data, compact }: { data: ReportDatum[]; compact: boolean }) {
  const keys = chartKeys(data);
  return <div className={`recharts-report ${compact ? 'compact' : ''}`}><ResponsiveContainer width="100%" height="100%"><BarChart data={chartRows(data)} margin={{ top: 8, right: 8, left: compact ? -25 : 0, bottom: 4 }}><CartesianGrid stroke="var(--line)" strokeDasharray="3 5" vertical={false} /><XAxis dataKey="_label" tick={{ fill: 'var(--muted)', fontSize: 9 }} axisLine={false} tickLine={false} /><YAxis hide={compact} /><Tooltip {...chartTooltip} /><Legend wrapperStyle={{ fontSize: 9 }} />{keys.map((key, index) => <Bar key={key} dataKey={key} stackId="total" fill={chartColors[index % chartColors.length]} radius={index === keys.length - 1 ? [5, 5, 0, 0] : undefined} />)}</BarChart></ResponsiveContainer></div>;
}

function RadarReport({ data }: { data: ReportDatum[] }) {
  return <div className="recharts-report"><ResponsiveContainer width="100%" height="100%"><RadarChart data={chartRows(data).slice(0, 10)} outerRadius="72%"><PolarGrid stroke="var(--line)" /><PolarAngleAxis dataKey="_label" tick={{ fill: 'var(--muted)', fontSize: 10 }} /><PolarRadiusAxis tick={{ fill: 'var(--muted)', fontSize: 9 }} /><Radar dataKey="_value" name="Value" stroke={chartColors[1]} fill={chartColors[1]} fillOpacity={.24} strokeWidth={2} /><Tooltip {...chartTooltip} /></RadarChart></ResponsiveContainer></div>;
}

function ScatterReport({ data }: { data: ReportDatum[] }) {
  const keys = chartKeys(data); const rows = data.slice(0, 30).map((row, index) => ({ x: Number(row[keys[0]] ?? index + 1), y: Number(row[keys[1] ?? keys[0]] ?? reportValue(row)), label: reportLabel(row, index) }));
  return <div className="recharts-report"><ResponsiveContainer width="100%" height="100%"><ScatterChart margin={{ top: 15, right: 20, bottom: 10, left: 0 }}><CartesianGrid stroke="var(--line)" strokeDasharray="3 5" /><XAxis type="number" dataKey="x" name={keys[0] ?? 'Index'} tick={{ fill: 'var(--muted)', fontSize: 10 }} /><YAxis type="number" dataKey="y" name={keys[1] ?? keys[0] ?? 'Value'} tick={{ fill: 'var(--muted)', fontSize: 10 }} /><Tooltip {...chartTooltip} cursor={{ strokeDasharray: '3 3' }} /><Scatter data={rows} fill={chartColors[0]} /></ScatterChart></ResponsiveContainer></div>;
}

function HeatmapReport({ data }: { data: ReportDatum[] }) {
  const keys = chartKeys(data); const values = data.flatMap(row => keys.map(key => Number(row[key] ?? 0))); const max = Math.max(...values, 1);
  return <div className="report-heatmap"><div className="heatmap-head"><span />{keys.map(key => <strong key={key}>{key.replace(/([A-Z])/g, ' $1')}</strong>)}</div>{data.slice(0, 10).map((row, index) => <div key={reportLabel(row, index)}><span>{reportLabel(row, index)}</span>{keys.map(key => { const value = Number(row[key] ?? 0); return <b key={key} title={`${key}: ${value}`} style={{ background: `color-mix(in srgb, ${chartColors[0]} ${Math.max(8, value / max * 100)}%, var(--surface))` }}>{formatNumber(value)}</b>; })}</div>)}</div>;
}

function TableReport({ data, compact }: { data: ReportDatum[]; compact: boolean }) {
  const columns = Object.keys(data[0] ?? {}).slice(0, compact ? 3 : 8);
  return <div className="report-data-table"><div>{columns.map(column => <strong key={column}>{column.replace(/([A-Z])/g, ' $1')}</strong>)}</div>{data.slice(0, compact ? 3 : 12).map((row, index) => <div key={index}>{columns.map(column => <span key={column}>{String(row[column] ?? '—')}</span>)}</div>)}</div>;
}

function KpiReport({ data, fallback }: { data: ReportDatum[]; fallback: number }) {
  const row = data[0]; const value = numericValues(row)[0];
  return <div className="report-kpi"><span>{value?.[0]?.replace(/([A-Z])/g, ' $1') ?? reportLabel(row, 0)}</span><strong>{formatNumber(value?.[1] ?? fallback)}</strong><small>{data.length > 1 ? `${formatNumber(data.length)} contributing rows` : 'Current value'}</small></div>;
}

function SettingsPage({ dark, setDark }: { dark: boolean; setDark: (value: boolean) => void }) {
  const workspace = useRemote(governed.workspace);
  const [form, setForm] = useState<Omit<WorkspaceView, 'id' | 'name' | 'queriesToday'> | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  useEffect(() => { if (workspace.data) setForm({ maxQueriesPerDay: workspace.data.maxQueriesPerDay, maxRows: workspace.data.maxRows, queryTimeoutSeconds: workspace.data.queryTimeoutSeconds, staleAfterMinutes: workspace.data.staleAfterMinutes }); }, [workspace.data]);
  const change = (field: keyof NonNullable<typeof form>, value: number) => setForm(current => current ? { ...current, [field]: value } : current);
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!form) return; setSaving(true); setMessage('');
    try { await governed.setLimits(form); setMessage('Workspace settings saved.'); await workspace.refresh(); }
    catch { setMessage('Could not save workspace settings.'); }
    finally { setSaving(false); }
  };
  if (workspace.loading && !workspace.data) return <LoadingState label="Loading workspace settings..." />;
  if (workspace.error && !workspace.data) return <ErrorState message={workspace.error} onRetry={workspace.refresh} />;
  return <div className="settings-grid"><section className="settings-card"><header><span><SlidersHorizontal /></span><div><h2>Workspace limits</h2><p>Control safe query execution for {workspace.data?.name}.</p></div></header><form onSubmit={event => void save(event)}><div className="settings-fields"><label>Queries per day<input type="number" min="1" required value={form?.maxQueriesPerDay ?? ''} onChange={event => change('maxQueriesPerDay', Number(event.target.value))} /></label><label>Maximum rows<input type="number" min="1" required value={form?.maxRows ?? ''} onChange={event => change('maxRows', Number(event.target.value))} /></label><label>Query timeout (seconds)<input type="number" min="1" required value={form?.queryTimeoutSeconds ?? ''} onChange={event => change('queryTimeoutSeconds', Number(event.target.value))} /></label><label>Stale after (minutes)<input type="number" min="1" required value={form?.staleAfterMinutes ?? ''} onChange={event => change('staleAfterMinutes', Number(event.target.value))} /></label></div><div className="settings-save"><span>{workspace.data?.queriesToday ?? 0} queries used today</span><button className="primary" disabled={saving || !form}>{saving ? 'Saving...' : 'Save limits'}</button></div>{message && <p className="settings-message" role="status">{message}</p>}</form></section><section className="settings-card appearance-card"><header><span><Moon /></span><div><h2>Appearance</h2><p>Choose the workspace theme for this device.</p></div></header><button className={`theme-choice ${!dark ? 'active' : ''}`} onClick={() => setDark(false)}><span className="theme-preview light" /><span><strong>Light Metric Mint</strong><small>Warm, bright analytics workspace</small></span>{!dark && <Check />}</button><button className={`theme-choice ${dark ? 'active' : ''}`} onClick={() => setDark(true)}><span className="theme-preview dark" /><span><strong>Dark Metric Mint</strong><small>Low-light operational workspace</small></span>{dark && <Check />}</button></section></div>;
}

function HelpCenter({ onNavigate }: { onNavigate: (page: Page) => void }) {
  return <div className="help-center"><section className="help-hero"><span className="eyebrow"><CircleHelp size={14} /> Quick start</span><h2>From source to insight in three steps</h2><p>Connect governed data, explore trusted metrics, then publish a decision-ready report or AI dashboard.</p><div><button onClick={() => onNavigate('sources')}><Database /><strong>1. Connect data</strong><small>Add SQL, SAP, Salesforce, Icertis, SharePoint, REST, or file sources.</small><ArrowRight /></button><button onClick={() => onNavigate('explore')}><Table2 /><strong>2. Explore metrics</strong><small>Build governed queries with visible filters, freshness, and generated SQL.</small><ArrowRight /></button><button onClick={() => onNavigate('ask')}><WandSparkles /><strong>3. Create insights</strong><small>Start a conversation and explore charts and tables from your data.</small><ArrowRight /></button></div></section><section className="faq-card"><h2>Frequently asked questions</h2><details><summary>How do I open a report template?</summary><p>Go to Reports and use the visible Open report button on any source-backed workbook. You can switch its chart type without changing the underlying data.</p></details><details><summary>Which visualizations are available?</summary><p>Bar, column, line, area, pie, donut, stacked bar, radar, scatter, heatmap, KPI, and table views are available.</p></details><details><summary>How does AI use my data?</summary><p>New chat sends your question to the backend analytics API and renders only the permitted rows, explanation, and warnings returned by the backend.</p></details><details><summary>Where can I create a custom metric?</summary><p>Open Governance, choose Metrics, and use New metric. Definitions can be tested, verified, approved, versioned, and rolled back.</p></details></section></div>;
}
function RefreshBar({ refreshed, refreshing, onRefresh }: { refreshed: string; refreshing: boolean; onRefresh: () => Promise<void> }) { return <div className="refresh-bar"><span><Database size={15} /> Live data from your connected source <small>Last refreshed {refreshed ? new Date(refreshed).toLocaleString() : 'just now'}</small></span><button onClick={() => void onRefresh()} disabled={refreshing}><RefreshCw size={14} className={refreshing ? 'spin' : ''} />{refreshing ? 'Refreshing cache' : 'Refresh cache'}</button></div>; }
function Card({ title, subtitle, action, children, className = '' }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode; className?: string }) { return <article className={`card ${className}`}><header><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</header>{children}</article>; }
function LoadingState({ label }: { label: string }) { return <div className="loading-state" role="status"><span className="spinner" /><strong>{label}</strong><small>This usually takes a few seconds.</small></div>; }
function ErrorState({ message, onRetry }: { message: string; onRetry?: () => Promise<void> }) { return <div className="state-card error-state" role="alert"><Database /><strong>Live data unavailable</strong><p>{message}</p>{onRetry && <button className="secondary" onClick={() => void onRetry()}><RefreshCw size={14} /> Retry</button>}</div>; }
function EmptyState({ text }: { text: string }) { return <div className="empty-state"><Table2 size={22} /><span>{text}</span></div>; }
function Modal({ title, subtitle, onClose, children, wide = false }: { title: string; subtitle: string; onClose: () => void; children: ReactNode; wide?: boolean }) { return <div className="modal-backdrop" role="presentation" onMouseDown={onClose}><div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} onMouseDown={event => event.stopPropagation()}><header><div><h2>{title}</h2><p>{subtitle}</p></div><button onClick={onClose} aria-label="Close"><X /></button></header>{children}</div></div>; }
