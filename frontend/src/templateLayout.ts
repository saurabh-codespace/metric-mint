import type { TemplateDefinition } from './api/templates';

export function parseTemplateUpload(text: string): unknown {
  if (new TextEncoder().encode(text).length > 65536) throw new Error('Template files must be 64 KB or smaller.');
  try { return JSON.parse(text); }
  catch { throw new Error('This file is not valid JSON. Upload a downloaded report template.'); }
}

export function serializeTemplate(definition: TemplateDefinition): string {
  return JSON.stringify({
    version: definition.version, name: definition.name, description: definition.description,
    widgets: definition.widgets.map(({ id, title, sourceType, sourceId, chart, labelKey, valueKey, columns, width }) =>
      ({ id, title, sourceType, sourceId, chart, labelKey, valueKey, columns, width })),
  }, null, 2);
}
