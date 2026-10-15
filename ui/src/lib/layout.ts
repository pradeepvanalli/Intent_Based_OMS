/* Layered (Sugiyama-lite) layout for state diagrams: longest-path layering from the initial state, barycentre ordering. */
export interface GNode { id: string; terminal?: boolean; initial?: boolean; }
export interface GEdge { from: string; to: string; label: string; id: string; }
export interface Placed { id: string; x: number; y: number; w: number; h: number; layer: number; terminal?: boolean; initial?: boolean; }
export interface EdgePath { id: string; from: string; to: string; label: string; d: string; lx: number; ly: number; back: boolean; self: boolean; }
export interface LayoutResult { nodes: Placed[]; edges: EdgePath[]; width: number; height: number; }

export function layoutGraph(nodes: GNode[], edges: GEdge[], opts: { nodeW?: number; nodeH?: number; gapX?: number; gapY?: number; charW?: number } = {}): LayoutResult {
  const nodeH = opts.nodeH ?? 36; const gapX = opts.gapX ?? 72; const gapY = opts.gapY ?? 28; const charW = opts.charW ?? 7.2;
  const ids = nodes.map((n) => n.id); const idx = new Map(ids.map((id, i) => [id, i]));
  const adj = new Map<string, string[]>(); ids.forEach((id) => adj.set(id, []));
  for (const e of edges) if (idx.has(e.from) && idx.has(e.to) && e.from !== e.to) adj.get(e.from)!.push(e.to);
  // layer = longest path from initial (DFS with cycle guard)
  const layer = new Map<string, number>();
  const roots = nodes.filter((n) => n.initial).map((n) => n.id);
  const start = roots.length ? roots : ids.filter((id) => !edges.some((e) => e.to === id && e.from !== id)).concat(ids.slice(0, 1));
  const onStack = new Set<string>();
  const visit = (id: string, depth: number) => {
    if (onStack.has(id)) return; // back edge
    if ((layer.get(id) ?? -1) >= depth) return;
    layer.set(id, depth); onStack.add(id);
    for (const t of adj.get(id) ?? []) visit(t, depth + 1);
    onStack.delete(id);
  };
  for (const s of start) visit(s, 0);
  ids.forEach((id) => { if (!layer.has(id)) layer.set(id, 0); });
  // terminal states go to last layer
  const maxL = Math.max(0, ...layer.values());
  nodes.forEach((n) => { if (n.terminal) layer.set(n.id, Math.max(layer.get(n.id)!, maxL)); });
  const layers: string[][] = [];
  ids.forEach((id) => { const l = layer.get(id)!; (layers[l] ??= []).push(id); });
  for (let l = 0; l < layers.length; l++) layers[l] ??= [];
  // barycentre ordering, two sweeps
  const pos = new Map<string, number>(); layers.forEach((ls) => ls.forEach((id, i) => pos.set(id, i)));
  for (let sweep = 0; sweep < 3; sweep++) {
    for (let l = 1; l < layers.length; l++) {
      const score = (id: string) => { const prev = edges.filter((e) => e.to === id && layer.get(e.from) === l - 1).map((e) => pos.get(e.from) ?? 0); return prev.length ? prev.reduce((a, b) => a + b, 0) / prev.length : pos.get(id) ?? 0; };
      layers[l].sort((a, b) => score(a) - score(b)); layers[l].forEach((id, i) => pos.set(id, i));
    }
  }
  const widthOf = (id: string) => Math.max(88, id.length * charW + 28);
  const colW = layers.map((ls) => Math.max(0, ...ls.map(widthOf)));
  // gap after each layer sized by the longest label of an adjacent-layer edge leaving it
  const labelW = (l: string) => l.length * 5.6 + 24;
  const gapAfter = layers.map((_, l) => Math.max(gapX, ...edges.filter((e) => layer.get(e.from) === l && layer.get(e.to) === l + 1).map((e) => labelW(e.label) + 16)));
  const maxRows = Math.max(1, ...layers.map((ls) => ls.length));
  const totalH = maxRows * nodeH + (maxRows - 1) * gapY;
  const placed: Placed[] = []; let x = 24;
  layers.forEach((ls, l) => {
    const h = ls.length * nodeH + (ls.length - 1) * gapY; const y0 = 24 + (totalH - h) / 2;
    ls.forEach((id, i) => { const n = nodes[idx.get(id)!]; placed.push({ id, x: x + (colW[l] - widthOf(id)) / 2, y: y0 + i * (nodeH + gapY), w: widthOf(id), h: nodeH, layer: l, terminal: n.terminal, initial: n.initial }); });
    x += colW[l] + gapAfter[l];
  });
  const width = x - gapAfter[layers.length - 1] + 24;
  const pmap = new Map(placed.map((p) => [p.id, p]));
  const out: EdgePath[] = [];
  const seenPair = new Map<string, number>();
  const graphBottom = 24 + totalH;
  let lane = 0; let selfLoops = 0;
  for (const e of edges) {
    const a = pmap.get(e.from); const b = pmap.get(e.to); if (!a || !b) continue;
    const key = `${e.from}>${e.to}`; const k = seenPair.get(key) ?? 0; seenPair.set(key, k + 1);
    if (a === b) { selfLoops++; const cx = a.x + a.w / 2; const top = a.y; const off = k * 14; out.push({ id: e.id, from: e.from, to: e.to, label: e.label, d: `M ${cx - 14} ${top} C ${cx - 30} ${top - 34 - off}, ${cx + 30} ${top - 34 - off}, ${cx + 14} ${top}`, lx: cx, ly: top - 32 - off, back: false, self: true }); continue; }
    const span = b.layer - a.layer;
    if (span === 1) {
      const off = k * 12;
      const x1 = a.x + a.w; const y1 = a.y + a.h / 2 + off; const x2 = b.x; const y2 = b.y + b.h / 2 + off; const mx = (x1 + x2) / 2;
      out.push({ id: e.id, from: e.from, to: e.to, label: e.label, d: `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`, lx: mx, ly: (y1 + y2) / 2 - 7 - (k % 2 ? 0 : 0), back: false, self: false });
    } else {
      // long forward edges and back edges travel below the graph, one lane each
      const yb = graphBottom + 22 + lane * 20; lane++;
      const x1 = a.x + a.w / 2 + (span < 0 ? -10 : 10); const y1 = a.y + a.h; const x2 = b.x + b.w / 2 + (span < 0 ? 10 : -10); const y2 = b.y + b.h;
      out.push({ id: e.id, from: e.from, to: e.to, label: e.label, d: `M ${x1} ${y1} C ${x1} ${yb}, ${x1} ${yb}, ${x1 + (x2 > x1 ? 20 : -20)} ${yb} L ${x2 - (x2 > x1 ? 20 : -20)} ${yb} C ${x2} ${yb}, ${x2} ${yb}, ${x2} ${y2}`, lx: (x1 + x2) / 2, ly: yb - 7, back: span <= 0, self: false });
    }
  }
  const height = graphBottom + 24 + (lane ? lane * 20 + 8 : 0) + (selfLoops ? 30 : 0);
  return { nodes: placed, edges: out, width, height };
}
