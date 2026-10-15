/* Line diff (LCS) producing side-by-side rows. */
export type DiffRow = { kind: 'same' | 'add' | 'del' | 'chg'; left?: string; right?: string; ln?: number; rn?: number };
export function lineDiff(a: string, b: string): DiffRow[] {
  const A = a.split('\n'); const B = b.split('\n');
  const n = A.length; const m = B.length;
  const dp: Uint16Array[] = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const rows: DiffRow[] = []; let i = 0; let j = 0;
  const pendDel: string[] = []; const pendAdd: string[] = []; let delStart = 0; let addStart = 0;
  const flush = () => {
    const k = Math.max(pendDel.length, pendAdd.length);
    for (let x = 0; x < k; x++) {
      const l = pendDel[x]; const r = pendAdd[x];
      rows.push({ kind: l !== undefined && r !== undefined ? 'chg' : l !== undefined ? 'del' : 'add', left: l, right: r, ln: l !== undefined ? delStart + x + 1 : undefined, rn: r !== undefined ? addStart + x + 1 : undefined });
    }
    pendDel.length = 0; pendAdd.length = 0;
  };
  while (i < n || j < m) {
    if (i < n && j < m && A[i] === B[j]) { flush(); rows.push({ kind: 'same', left: A[i], right: B[j], ln: i + 1, rn: j + 1 }); i++; j++; }
    else if (j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j])) { if (!pendAdd.length) addStart = j; pendAdd.push(B[j]); j++; }
    else { if (!pendDel.length) delStart = i; pendDel.push(A[i]); i++; }
  }
  flush();
  return rows;
}
export function diffStats(rows: DiffRow[]): { added: number; removed: number; changed: number } {
  return { added: rows.filter((r) => r.kind === 'add').length, removed: rows.filter((r) => r.kind === 'del').length, changed: rows.filter((r) => r.kind === 'chg').length };
}
/** Unified diff text (hunks with 3 lines of context) built from the LCS rows. */
export function unifiedDiff(a: string, b: string, aName = 'before', bName = 'after', context = 3): string {
  const rows = lineDiff(a, b);
  const out: string[] = [`--- ${aName}`, `+++ ${bName}`];
  // group changed rows into hunks; merge groups separated by <= 2*context unchanged rows
  const groups: [number, number][] = [];
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].kind === 'same') continue;
    const last = groups[groups.length - 1];
    if (last && i - last[1] <= context * 2) last[1] = i; else groups.push([i, i]);
  }
  for (const [s, e] of groups) {
    const from = Math.max(0, s - context); const to = Math.min(rows.length, e + 1 + context);
    const slice = rows.slice(from, to);
    const oldStart = slice.find((r) => r.ln !== undefined)?.ln ?? 1; const newStart = slice.find((r) => r.rn !== undefined)?.rn ?? 1;
    const oldCount = slice.filter((r) => r.kind !== 'add').length; const newCount = slice.filter((r) => r.kind !== 'del').length;
    out.push(`@@ -${oldStart},${oldCount} +${newStart},${newCount} @@`);
    for (const r of slice) {
      if (r.kind === 'same') out.push(` ${r.left ?? ''}`);
      else if (r.kind === 'del') out.push(`-${r.left ?? ''}`);
      else if (r.kind === 'add') out.push(`+${r.right ?? ''}`);
      else { out.push(`-${r.left ?? ''}`); out.push(`+${r.right ?? ''}`); }
    }
  }
  return out.join('\n');
}
