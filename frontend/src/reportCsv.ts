import type { ReportDatum } from './api/client';

export function reportCsv(rows: ReportDatum[]) {
  const columns = Object.keys(rows[0] ?? {});
  const escape = (value: unknown) => {
    const text = String(value ?? '');
    const safe = typeof value === 'string' && /^[\s]*[=+@-]/.test(text) ? `'${text}` : text;
    return `"${safe.replace(/"/g, '""')}"`;
  };
  return [columns, ...rows.map(row => columns.map(column => row[column]))].map(row => row.map(escape).join(',')).join('\r\n');
}
