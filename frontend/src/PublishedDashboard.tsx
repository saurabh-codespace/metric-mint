import { useEffect, useState } from 'react';
import { Globe2, ShieldCheck } from 'lucide-react';
import { publications, type Publication } from './api/publications';
import { workbooks, type WorkbookReport } from './api/workbooks';
import { Dashboard } from './Dashboard';
import { WorkbookReportView } from './WorkbookReports';
import { templates, type RunResult } from './api/templates';
import { TemplateReportView } from './ReportTemplates';

export function PublishedDashboard({ token }: { token: string }) {
  const [publication, setPublication] = useState<Publication | null>(null);
  const [report, setReport] = useState<WorkbookReport | null>(null);
  const [template, setTemplate] = useState<RunResult | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setPublication(null); setReport(null); setTemplate(null); setError('');
    void publications.get(token).then(async result => {
      const workbook = result.kind === 'workbook' && result.reportId ? await workbooks.get(result.reportId) : null;
      const snapshot = result.kind === 'template' ? await templates.published(token) : null;
      if (active) { setPublication(result); setReport(workbook); setTemplate(snapshot); }
    }).catch(() => { if (active) setError('This dashboard link is unavailable. It may have been revoked, or your account belongs to a different workspace.'); });
    return () => { active = false; };
  }, [token]);
  if (error) return <section className="state-card" role="alert"><ShieldCheck /><h1>Dashboard unavailable</h1><p>{error}</p></section>;
  if (!publication) return <p role="status">Opening your published dashboard…</p>;
  return <><header className="shared-dashboard-banner"><Globe2 size={22} /><div><span>Published dashboard · Sign-in protected</span><h1>{publication.name}</h1><p>Published by {publication.ownerName} · {new Date(publication.publishedAt).toLocaleDateString()} · {publication.kind === 'template' ? 'Published snapshot' : 'Sample data'}</p></div></header>{publication.kind === 'template' ? template && <TemplateReportView result={template} /> : publication.kind === 'executive' ? <Dashboard key={publication.id} published={publication} /> : report && <WorkbookReportView key={publication.id} report={report} initialChart={publication.chart} />}</>;
}
