import type { ReactNode } from 'react';
import { EmptyState } from './States';

export interface Column<T> { key: string; header: ReactNode; render: (row: T) => ReactNode; width?: string | number; align?: 'left' | 'right'; className?: string; }
export interface TableProps<T> {
  rows: T[]; columns: Column<T>[]; rowKey: (row: T) => string; onRowClick?: (row: T) => void; dense?: boolean; empty?: ReactNode; emptyTitle?: string;
  selectable?: { selected: Set<string>; onToggle: (id: string) => void; onToggleAll: (ids: string[]) => void; }; selectedKey?: string | null; caption?: string; loading?: boolean;
}
export function Table<T>({ rows, columns, rowKey, onRowClick, dense, empty, emptyTitle, selectable, selectedKey, caption, loading }: TableProps<T>) {
  const ids = rows.map(rowKey);
  const allSel = selectable && ids.length > 0 && ids.every((id) => selectable.selected.has(id));
  const someSel = selectable && !allSel && ids.some((id) => selectable.selected.has(id));
  return (
    <div className="table-wrap">
      <table className={['tbl', dense && 'dense'].filter(Boolean).join(' ')}>
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {selectable && <th className="tbl-check"><input type="checkbox" aria-label="Select all rows" checked={!!allSel} ref={(el) => { if (el) el.indeterminate = !!someSel; }} onChange={() => selectable.onToggleAll(ids)} /></th>}
            {columns.map((c) => <th key={c.key} style={{ width: c.width }} className={[c.align === 'right' && 'num', c.className].filter(Boolean).join(' ')} scope="col">{c.header}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && !loading && <tr><td colSpan={columns.length + (selectable ? 1 : 0)} style={{ padding: 0 }}>{empty ?? <EmptyState title={emptyTitle ?? 'Nothing here'} />}</td></tr>}
          {rows.map((r) => { const id = rowKey(r); const sel = selectable?.selected.has(id) || selectedKey === id; return (
            <tr key={id} className={[onRowClick && 'clickable', sel && 'selected'].filter(Boolean).join(' ')} onClick={onRowClick ? () => onRowClick(r) : undefined} tabIndex={onRowClick ? 0 : undefined} onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(r); } } : undefined} aria-selected={sel || undefined}>
              {selectable && <td className="tbl-check" onClick={(e) => e.stopPropagation()}><input type="checkbox" aria-label={`Select ${id}`} checked={selectable.selected.has(id)} onChange={() => selectable.onToggle(id)} onKeyDown={(e) => e.stopPropagation()} /></td>}
              {columns.map((c) => <td key={c.key} className={[c.align === 'right' && 'num', c.className].filter(Boolean).join(' ')}>{c.render(r)}</td>)}
            </tr>); })}
        </tbody>
      </table>
    </div>
  );
}
export function Pagination({ offset, limit, total, onChange }: { offset: number; limit: number; total: number; onChange: (offset: number) => void }) {
  if (total <= 0) return null;
  const from = offset + 1; const to = Math.min(total, offset + limit);
  return (
    <nav className="pagination" aria-label="Pagination">
      <span className="num">{from}–{to} of {total.toLocaleString()}</span>
      <div className="row">
        <button className="btn btn-sm" onClick={() => onChange(Math.max(0, offset - limit))} disabled={offset === 0}>Previous</button>
        <button className="btn btn-sm" onClick={() => onChange(offset + limit)} disabled={to >= total}>Next</button>
      </div>
    </nav>
  );
}
