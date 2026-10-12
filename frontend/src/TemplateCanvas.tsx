import { useRef, useState, type DragEvent } from 'react';
import { ArrowDown, ArrowUp, BarChart3, Columns2, Database, GripVertical, LineChart, AreaChart, PieChart, Donut, Plus, Settings2, Table2 } from 'lucide-react';
import type { TemplateWidget } from './api/templates';

const blocks = [
  { chart: 'bar', label: 'Bar chart', icon: BarChart3 },
  { chart: 'column', label: 'Column chart', icon: Columns2 },
  { chart: 'line', label: 'Line chart', icon: LineChart },
  { chart: 'area', label: 'Area chart', icon: AreaChart },
  { chart: 'pie', label: 'Pie chart', icon: PieChart },
  { chart: 'donut', label: 'Donut chart', icon: Donut },
  { chart: 'table', label: 'Table', icon: Table2 },
] as const;
type DragItem = { chart: TemplateWidget['chart'] } | { id: string };

export function TemplateCanvas({ widgets, disabled, onChange, onConfigure }: {
  widgets: TemplateWidget[]; disabled: boolean;
  onChange: (widgets: TemplateWidget[]) => void; onConfigure: (id: string) => void;
}) {
  const drag = useRef<DragItem | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const finish = () => { drag.current = null; setTarget(null); };
  const place = (item: DragItem, before: string | null) => {
    if (disabled || ('chart' in item && widgets.length >= 12)) return;
    if ('id' in item && (item.id === before || !widgets.some(widget => widget.id === item.id))) return;
    const widget: TemplateWidget = 'id' in item ? widgets.find(widget => widget.id === item.id)! : {
      id: crypto.randomUUID(), title: blocks.find(block => block.chart === item.chart)!.label,
      sourceType: 'workbook', sourceId: '', chart: item.chart, labelKey: '', valueKey: '', columns: [],
      width: item.chart === 'table' ? 'full' : 'half',
    };
    const next = widgets.filter(existing => existing.id !== widget.id);
    const index = before ? next.findIndex(existing => existing.id === before) : next.length;
    if (index < 0) return;
    next.splice(index, 0, widget);
    onChange(next);
    setAnnouncement(`${widget.title} ${'id' in item ? 'moved' : 'added'} to position ${index + 1}.`);
  };
  const start = (event: DragEvent, item: DragItem) => {
    if (disabled || ('chart' in item && widgets.length >= 12)) { event.preventDefault(); return; }
    drag.current = item;
    event.dataTransfer.effectAllowed = 'id' in item ? 'move' : 'copy';
    event.dataTransfer.setData('application/x-metric-mint-widget', 'widget');
  };
  const over = (event: DragEvent, id: string) => {
    if (!drag.current || disabled) return;
    event.preventDefault(); event.stopPropagation();
    event.dataTransfer.dropEffect = 'id' in drag.current ? 'move' : 'copy';
    setTarget(id);
  };
  const drop = (event: DragEvent, before: string | null) => {
    if (!drag.current) return;
    event.preventDefault(); event.stopPropagation();
    place(drag.current, before); finish();
  };
  return <section className="template-visual-builder" aria-label="Visual layout builder">
    <div className="template-palette-heading"><div><h3>Drag. Drop. Make it yours.</h3><p>Drag a chart or table onto the canvas, or click to add it.</p></div><span>{widgets.length}/12</span></div>
    <div className="template-palette" aria-label="Chart and table palette">{blocks.map(({ chart, label, icon: Icon }) =>
      <button key={chart} type="button" className={`template-block block-${chart}`} aria-label={`Add ${label.toLowerCase()}`}
        disabled={disabled || widgets.length >= 12} draggable={!disabled && widgets.length < 12}
        onDragStart={event => start(event, { chart })} onDragEnd={finish} onClick={() => place({ chart }, null)}>
        <Icon size={23} /><span>{label}</span><GripVertical size={13} />
      </button>)}</div>
    <div className="template-drop-canvas" aria-label="Layout canvas" onDragOver={event => over(event, 'end')}
      onDrop={event => drop(event, null)} onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setTarget(null); }}>
      <div className="template-layout-grid">{widgets.map((widget, index) => {
        const Icon = blocks.find(block => block.chart === widget.chart)!.icon;
        return <article key={widget.id} data-widget-id={widget.id} className={`template-layout-tile ${widget.width} ${target === widget.id ? 'drop-before' : ''}`}
          onDragOver={event => over(event, widget.id)} onDrop={event => drop(event, widget.id)}>
          <header><button type="button" className="template-drag-handle" aria-label={`Drag ${widget.title}`} title="Drag to reorder; use the move buttons with a keyboard"
            disabled={disabled} draggable={!disabled} onDragStart={event => start(event, { id: widget.id })} onDragEnd={finish}><GripVertical size={18} /></button>
            <strong>{widget.title}</strong><span>{index + 1}</span></header>
          <button type="button" className={`template-tile-content block-${widget.chart}`} disabled={disabled} onClick={() => onConfigure(widget.id)} aria-label={`Configure ${widget.title}`}>
            <Icon size={40} /><span><Database size={13} />{widget.sourceId ? 'Data source selected' : 'Choose a data source'}</span>
          </button>
          <footer><button type="button" disabled={disabled} onClick={() => onConfigure(widget.id)}><Settings2 size={13} /> Configure</button>
            <button type="button" disabled={disabled} aria-label={`Make ${widget.title} ${widget.width === 'half' ? 'full' : 'half'} width`}
              onClick={() => onChange(widgets.map(item => item.id === widget.id ? { ...item, width: item.width === 'half' ? 'full' : 'half' } : item))}>{widget.width === 'half' ? 'Half width' : 'Full width'}</button>
            <button type="button" aria-label={`Move ${widget.title} earlier`} disabled={disabled || index === 0} onClick={() => place({ id: widget.id }, widgets[index - 1].id)}><ArrowUp size={14} /></button>
            <button type="button" aria-label={`Move ${widget.title} later`} disabled={disabled || index === widgets.length - 1} onClick={() => place({ id: widget.id }, widgets[index + 2]?.id ?? null)}><ArrowDown size={14} /></button>
          </footer>
        </article>;
      })}</div>
      <div className={`template-drop-target ${target === 'end' ? 'active' : ''}`}><Plus size={24} /><strong>{widgets.length ? 'Drop here to add at the end' : 'Drop your first chart or table here'}</strong><span>Drag the grip on a widget to rearrange it. Use Configure to connect your data.</span></div>
    </div>
    <p className="sr-only" role="status" aria-live="polite">{announcement}</p>
  </section>;
}
