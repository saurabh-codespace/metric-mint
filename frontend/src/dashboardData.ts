import type { ToscanaOverview } from './api/client';

export const businessUnits = ['Technology', 'Operations', 'Finance', 'Marketing', 'People'];
export type Invoice = { id: string; vendor: string; unit: string; date: string; amount: number; currency: string; status: 'Paid' | 'In review' | 'Scheduled' | 'Exception'; cycle: number; automated: boolean; age: number; asOf: string };
export function selectInvoices(invoices: Invoice[], days: number, unit = 'all', previous = false) {
  return invoices.filter(invoice => invoice.age >= (previous ? days : 0) && invoice.age < (previous ? days * 2 : days) && (unit === 'all' || invoice.unit === unit));
}
export function summarize(invoices: Invoice[]) {
  const paid = invoices.filter(invoice => invoice.status === 'Paid');
  const open = invoices.filter(invoice => invoice.status !== 'Paid');
  return {
    total: invoices.length, completed: paid.length, open: open.length,
    amount: invoices.reduce((sum, invoice) => sum + invoice.amount, 0),
    outstanding: open.reduce((sum, invoice) => sum + invoice.amount, 0),
    completion: invoices.length ? paid.length / invoices.length * 100 : 0,
    cycle: paid.length ? paid.reduce((sum, invoice) => sum + invoice.cycle, 0) / paid.length : 0,
    automation: invoices.length ? invoices.filter(invoice => invoice.automated).length / invoices.length * 100 : 0,
    sla: invoices.length ? invoices.filter(invoice => invoice.status === 'Paid' ? invoice.cycle <= 5 : invoice.age <= 5).length / invoices.length * 100 : 0,
    exceptions: invoices.filter(invoice => invoice.status === 'Exception').length,
  };
}

export function buildOverview(invoices: Invoice[]): ToscanaOverview {
  const summary = summarize(invoices);
  const months = [...new Set(invoices.map(invoice => invoice.date.slice(0, 7)))].sort();
  return {
    databaseName: 'Sample workspace', lastRefreshed: invoices[0]?.asOf ?? '', totalWorkItems: summary.total, completedWorkItems: summary.completed, openWorkItems: summary.open, completionRate: summary.completion,
    activeLobs: new Set(invoices.map(invoice => invoice.unit)).size,
    lobs: businessUnits.map(name => {
      const rows = invoices.filter(invoice => invoice.unit === name);
      const stats = summarize(rows);
      const open = rows.filter(invoice => invoice.status !== 'Paid');
      return { name, total: stats.total, completed: stats.completed, open: stats.open, avgAgeing: open.length ? open.reduce((sum, invoice) => sum + invoice.age, 0) / open.length : 0 };
    }).filter(lob => lob.total > 0),
    statuses: ['Paid', 'In review', 'Scheduled', 'Exception'].map(name => ({ name, count: invoices.filter(invoice => invoice.status === name).length })),
    monthlyVolume: months.map(period => ({ period, count: invoices.filter(invoice => invoice.date.startsWith(period)).length })),
    queues: [{ name: 'Invoice exceptions', count: summary.exceptions }, { name: 'Awaiting review', count: invoices.filter(invoice => invoice.status === 'In review').length }, { name: 'Payment scheduled', count: invoices.filter(invoice => invoice.status === 'Scheduled').length }],
  };
}

