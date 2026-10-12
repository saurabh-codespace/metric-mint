import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, ArrowRight, Database, FileBarChart, LayoutDashboard, Sparkles, WandSparkles } from 'lucide-react';
import { templates, type GeneratedDashboard, type RunResult } from './api/templates';
import { ApiError } from './api/client';
import { displayLabel } from './displayLabel';

const examples = {
  live: 'Create an invoice dashboard with monthly volume and status distribution',
  sample: 'Create a dashboard with queue management and utilization',
};

export function DashboardGenerator({ canEdit, onBack, onGenerated }: { canEdit: boolean; onBack: () => void; onGenerated: (result: RunResult, origin: Pick<GeneratedDashboard, 'aiGenerated' | 'message'>) => void }) {
  const [prompt, setPrompt] = useState('');
  const [dataMode, setDataMode] = useState<'live' | 'sample'>('live');
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [statusError, setStatusError] = useState(false);
  const [statusAttempt, setStatusAttempt] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState('');
  const [clarification, setClarification] = useState('');
  const [notice, setNotice] = useState('');
  const request = useRef<AbortController | null>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (!canEdit) return;
    const controller = new AbortController(); setStatusError(false); setConfigured(null);
    void templates.aiStatus(controller.signal).then(status => { if (!controller.signal.aborted) setConfigured(status.configured); }).catch(() => { if (!controller.signal.aborted) setStatusError(true); });
    return () => controller.abort();
  }, [canEdit, statusAttempt]);
  useEffect(() => () => request.current?.abort(), []);
  const back = () => { request.current?.abort(); request.current = null; onBack(); };
  const cancel = () => { request.current?.abort(); request.current = null; setGenerating(false); setNotice('Generation canceled. Your prompt is ready to edit.'); };
  const generate = async (event: FormEvent) => {
    event.preventDefault(); if (!canEdit || request.current || !prompt.trim()) return;
    const controller = new AbortController(); request.current = controller;
    setGenerating(true); setError(''); setClarification(''); setNotice('');
    try {
      const response = await templates.generate(prompt.trim(), dataMode, controller.signal);
      if (controller.signal.aborted) return;
      if (response.result) onGenerated(response.result, { aiGenerated: response.aiGenerated, message: response.message });
      else setClarification(response.clarification || response.message || 'Please describe the report you want and which measures to include.');
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof ApiError && cause.status === 403 ? 'Your account cannot generate this dashboard. Choose an available data source or ask your workspace administrator.' : 'Could not generate your dashboard. Your prompt is still here; please try again.');
    } finally { if (request.current === controller) { request.current = null; if (!controller.signal.aborted) setGenerating(false); } }
  };
  return <section className="dashboard-generator" aria-label="AI dashboard studio">
    <button className="secondary template-back" onClick={back}><ArrowLeft size={16} /> Template library</button>
    <header className="generator-heading"><span className="generator-mark"><WandSparkles size={25} /></span><span className="template-eyebrow">FROM A QUESTION TO A CLEARER VIEW</span><h1>Describe it.<br /><span>Make it a dashboard.</span></h1><p>Tell us what you want to understand. Start with a draft, make it yours on the canvas, and publish when it is ready.</p></header>
    {canEdit ? <form className="generator-form" onSubmit={event => void generate(event)}>
      <label className="generator-prompt-label" htmlFor="dashboard-prompt">Describe your dashboard</label>
      <textarea ref={textarea} id="dashboard-prompt" aria-label="Describe your dashboard" required maxLength={2000} rows={5} disabled={generating} value={prompt} onChange={event => { setPrompt(event.target.value); setError(''); setClarification(''); setNotice(''); }} placeholder={examples[dataMode]} />
      <div className="generator-prompt-meta"><span>Include the topics, comparisons, and chart styles you need.</span><span>{prompt.length}/2,000</span></div>
      <fieldset className="generator-data-modes" disabled={generating}><legend>Choose your data</legend><label className={dataMode === 'live' ? 'selected' : ''}><input type="radio" aria-label="Live data" name="dashboard-data-mode" value="live" checked={dataMode === 'live'} onChange={() => { setDataMode('live'); setClarification(''); }} /><Database size={20} /><span><strong>Live data</strong><small>Connected data and permitted saved reports</small></span></label><label className={dataMode === 'sample' ? 'selected' : ''}><input type="radio" aria-label="Sample data" name="dashboard-data-mode" value="sample" checked={dataMode === 'sample'} onChange={() => { setDataMode('sample'); setClarification(''); }} /><FileBarChart size={20} /><span><strong>Sample data</strong><small>Clearly marked demonstration records</small></span></label></fieldset>
      <div className="generator-provider-note" role="status"><Sparkles size={15} /><span>{configured === false ? 'AI is not configured. Starter layouts are available for supported report requests.' : configured ? 'AI drafts use the data sources available to your account. Review the result before publishing.' : statusError ? 'AI availability could not be checked.' : 'Checking AI availability…'}{statusError && <button type="button" onClick={() => setStatusAttempt(value => value + 1)}>Retry check</button>}</span></div>
      {clarification && <div className="generator-clarification" role="status"><strong>A little more detail would help.</strong><p>{displayLabel(clarification)}</p></div>}
      {error && <p className="generator-error" role="alert">{error}</p>}
      {notice && <p className="generator-notice" role="status">{notice}</p>}
      <div className="generator-actions"><span>{generating ? 'Preparing your editable draft…' : 'Review and edit before saving or publishing.'}</span>{generating && <button type="button" className="secondary" onClick={cancel}>Cancel generation</button>}<button className="primary" type="submit" aria-label="Generate dashboard" disabled={generating || !prompt.trim()}><WandSparkles size={17} />{generating ? 'Generating…' : 'Generate dashboard'}{!generating && <ArrowRight size={16} />}</button></div>
    </form> : <div className="generator-viewer-note"><LayoutDashboard size={23} /><h2>Your dashboards are ready to explore.</h2><p>A workspace analyst or administrator can generate and edit dashboards. Open the template library to view saved reports.</p><button className="secondary" onClick={onBack}>Open template library</button></div>}
    {canEdit && <div className="generator-examples"><span>TRY A STARTING POINT</span><button disabled={generating} onClick={() => { setPrompt(examples[dataMode]); setClarification(''); setError(''); textarea.current?.focus(); }}><LayoutDashboard size={18} /><span>{examples[dataMode]}</span><ArrowRight size={16} /></button></div>}
  </section>;
}
