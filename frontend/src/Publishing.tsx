import { useEffect, useRef, useState } from 'react';
import { Check, Copy, ExternalLink, Globe2, LockKeyhole, Rocket, Trash2, X } from 'lucide-react';
import { publications, publicationUrl, type DashboardDefinition, type Publication } from './api/publications';

export function PublishButton({ definition }: { definition: DashboardDefinition }) {
  const [open, setOpen] = useState(false);
  return <><button className="primary publish-button" onClick={() => setOpen(true)}><Rocket size={16} /> Publish dashboard</button>{open && <PublishDialog definition={definition} onClose={() => setOpen(false)} />}</>;
}

function PublishDialog({ definition, onClose }: { definition: DashboardDefinition; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [name, setName] = useState(definition.name.slice(0, 120));
  const [result, setResult] = useState<Publication | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { dialog.current?.showModal(); }, []);
  const publish = async () => {
    setBusy(true); setError('');
    try { setResult(await publications.publish({ ...definition, name: name.trim() })); }
    catch { setError('Could not publish. Check your connection and workspace publishing permissions, then retry.'); }
    finally { setBusy(false); }
  };
  return <dialog ref={dialog} className="publish-dialog" aria-labelledby="publish-title" onCancel={onClose} onClose={onClose}>
    <button className="dialog-close" aria-label="Close publish dialog" onClick={onClose}><X size={20} /></button>
    <span className="publication-status"><Globe2 size={18} /> WORKSPACE PUBLISHING</span>
    <h2 id="publish-title">{result ? 'Your dashboard is published.' : 'Give your insights a home.'}</h2>
    <p>{result ? 'Share this token link with a signed-in member of your workspace.' : 'Save this dashboard and its current view, then share a secure link with your team.'}</p>
    {result ? <><ShareLink publication={result} /><p className="report-demo-note"><LockKeyhole size={16} /> The link requires sign-in and the same workspace. You can revoke it from Reports → Published dashboards.</p><button className="primary" onClick={onClose}><Check size={16} /> Done</button></> : <form onSubmit={event => { event.preventDefault(); void publish(); }}>
      <label>Dashboard name<input autoFocus required maxLength={120} value={name} onChange={event => setName(event.target.value)} /></label>
      <p className="report-demo-note"><LockKeyhole size={16} /> Token + workspace sign-in required. {definition.kind === 'template' ? 'The layout is saved; data loads using the signed-in viewer permissions.' : 'Contains clearly marked sample data.'}</p>
      {error && <p role="alert">{error}</p>}
      <button className="primary" disabled={busy || !name.trim()}><Rocket size={16} />{busy ? 'Publishing…' : 'Publish and create link'}</button>
    </form>}
  </dialog>;
}

function ShareLink({ publication }: { publication: Publication }) {
  const [notice, setNotice] = useState('');
  const url = publicationUrl(publication.token);
  const copy = async () => {
    try { await navigator.clipboard.writeText(url); setNotice('Link copied'); }
    catch { setNotice('Select and copy the link below.'); }
  };
  return <div className="publication-link"><label>Shareable dashboard link<input aria-label="Shareable dashboard link" readOnly value={url} onFocus={event => event.target.select()} /></label><div><button className="secondary" onClick={() => void copy()}><Copy size={15} /> Copy link</button><a className="secondary" href={url}><ExternalLink size={15} /> Open dashboard</a></div><small role="status">{notice}</small></div>;
}

export function PublishedList() {
  const [items, setItems] = useState<Publication[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pending, setPending] = useState('');
  const [confirming, setConfirming] = useState('');
  const load = () => { setLoading(true); setError(''); void publications.list().then(setItems).catch(() => setError('Published dashboards could not be loaded.')).finally(() => setLoading(false)); };
  useEffect(load, []);
  const revoke = async (id: string) => {
    setPending(id); setError('');
    try { await publications.revoke(id); setItems(previous => previous.filter(item => item.id !== id)); setConfirming(''); }
    catch { setError('Could not revoke this link. Only its publisher or a workspace administrator can revoke it.'); }
    finally { setPending(''); }
  };
  return <section className="publication-panel"><div className="report-workspace-header"><div><span className="reports-eyebrow">SHARE WITH CONFIDENCE</span><h2>Published dashboards</h2><p>Saved views for your workspace. Every link requires a token and sign-in.</p></div><button className="secondary" onClick={load} disabled={loading}>Refresh</button></div>
    {error && <p role="alert">{error}</p>}{loading ? <p role="status">Loading published dashboards…</p> : items.length ? <div className="publication-list">{items.map(item => <article className="publication-card" key={item.id}><span className="publication-status"><Globe2 size={16} /> Published{item.kind !== 'template' && ' \u00b7 Sample data'}</span><h3>{item.name}</h3><p>{item.kind === 'executive' ? 'Executive analytics' : item.kind === 'template' ? 'Report template' : 'Workbook report'} · {new Date(item.publishedAt).toLocaleDateString()} · {item.ownerName}</p><ShareLink publication={item} />{item.canRevoke && (confirming === item.id ? <div><p>Revoke this link? It will stop opening the dashboard.</p><button className="secondary" disabled={pending === item.id} onClick={() => void revoke(item.id)}>{pending === item.id ? 'Revoking…' : 'Confirm revoke'}</button><button className="secondary" onClick={() => setConfirming('')}>Keep published</button></div> : <button className="secondary" onClick={() => setConfirming(item.id)}><Trash2 size={15} /> Revoke link</button>)}</article>)}</div> : <div className="state-card"><Globe2 /><h3>No dashboards published yet</h3><p>Open a saved template, workbook report, or executive dashboard and choose Publish dashboard.</p></div>}
  </section>;
}
