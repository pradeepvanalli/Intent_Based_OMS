import { useMemo, useState } from 'react';
import { diffStats, lineDiff, unifiedDiff } from '../lib/diff';
import { Badge } from './Badge';
import { YamlView } from './Misc';

type Mode = 'split' | 'unified' | 'after';

/** Diff viewer with side-by-side (LCS, src/lib/diff.ts) and unified modes. `diff` is the server's unified diff when present. */
export function DiffViewer({ before, after, diff, leftLabel = 'before', rightLabel = 'after', maxHeight }: { before: string | null; after: string; diff?: string; leftLabel?: string; rightLabel?: string; maxHeight?: number }) {
  const isNew = before === null || before === '';
  const [mode, setMode] = useState<Mode>(isNew ? 'after' : 'unified');
  const rows = useMemo(() => (isNew ? [] : lineDiff(before ?? '', after)), [before, after, isNew]);
  const stats = useMemo(() => diffStats(rows), [rows]);
  const unified = useMemo(() => diff ?? unifiedDiff(before ?? '', after, leftLabel, rightLabel), [diff, before, after, leftLabel, rightLabel]);
  const lineCount = after.replace(/\n$/, '').split('\n').length;
  return (
    <div className="col gap2">
      <div className="diff-tools">
        {isNew ? <Badge tone="ok">new · {lineCount} lines</Badge> : <><Badge tone="ok">+{stats.added + stats.changed}</Badge><Badge tone="danger">−{stats.removed + stats.changed}</Badge><span className="small muted">{leftLabel} → {rightLabel}</span></>}
        <span className="grow" />
        <div className="btn-group" role="group" aria-label="Diff view mode">
          {!isNew && <button type="button" className="btn btn-sm" aria-pressed={mode === 'split'} onClick={() => setMode('split')}>Side by side</button>}
          <button type="button" className="btn btn-sm" aria-pressed={mode === 'unified'} onClick={() => setMode('unified')}>Unified</button>
          <button type="button" className="btn btn-sm" aria-pressed={mode === 'after'} onClick={() => setMode('after')}>{isNew ? 'Content' : 'Result'}</button>
        </div>
      </div>
      {mode === 'split' && !isNew && (
        <div className="diff" role="table" aria-label={`Diff ${leftLabel} to ${rightLabel}`} style={maxHeight ? { maxHeight } : undefined}>
          <div className="side" role="rowgroup"><div className="dl" style={{ background: 'var(--surface-2)', fontWeight: 600, position: 'sticky', top: 0 }}>{leftLabel}</div>{rows.map((r, i) => <div key={i} className={`dl ${r.kind === 'chg' ? 'chg' : r.kind === 'del' ? 'del' : r.kind === 'add' ? 'pad' : ''}`}><span className="ln">{r.ln ?? ''}</span>{r.left ?? ''}</div>)}</div>
          <div className="side" role="rowgroup"><div className="dl" style={{ background: 'var(--surface-2)', fontWeight: 600, position: 'sticky', top: 0 }}>{rightLabel}</div>{rows.map((r, i) => <div key={i} className={`dl ${r.kind === 'chg' ? 'chg' : r.kind === 'add' ? 'add' : r.kind === 'del' ? 'pad' : ''}`}><span className="ln">{r.rn ?? ''}</span>{r.right ?? ''}</div>)}</div>
        </div>
      )}
      {mode === 'unified' && <UnifiedDiff text={unified} maxHeight={maxHeight} />}
      {mode === 'after' && <YamlView text={after} maxHeight={maxHeight ?? 420} />}
    </div>
  );
}

export function UnifiedDiff({ text, maxHeight }: { text: string; maxHeight?: number }) {
  const lines = text.replace(/\n$/, '').split('\n');
  const cls = (l: string) => (l.startsWith('+++') || l.startsWith('---') ? 'meta' : l.startsWith('@@') ? 'hunk' : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : '');
  return <pre className="udiff" aria-label="Unified diff" style={maxHeight ? { maxHeight } : undefined}>{lines.map((l, i) => <div key={i} className={`dl ${cls(l)}`}>{l || ' '}</div>)}</pre>;
}
