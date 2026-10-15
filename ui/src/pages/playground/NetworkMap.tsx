import type { Node } from '../../api/types';
import { HI_BOUNDS, OAHU_OUTLINE, US_BOUNDS, US_OUTLINE, arcPath, outlinePath, place, type Box } from './map';

const VIEW = { w: 620, h: 372 };
const MAIN: Box = { x: 10, y: 8, w: 600, h: 330 };
const INSET: Box = { x: 18, y: 284, w: 96, h: 66 };

export interface MapDestination { name: string; lat: number; lon: number; }

/**
 * Inline SVG map of the tenant network: every node, the destination pin and an animated dashed route from each node in
 * the chosen plan to the destination. No tiles, no libraries; equirectangular projection from map.ts.
 */
export function NetworkMap({ nodes, destination, chosen, pickup, label }: { nodes: Node[]; destination: MapDestination | null; chosen: string[]; pickup?: boolean; label?: string }) {
  const dest = destination ? place(destination.lat, destination.lon, MAIN, INSET) : null;
  const chosenSet = new Set(chosen);
  return (
    <svg className="pg-map" viewBox={`0 0 ${VIEW.w} ${VIEW.h}`} role="img" aria-label={label ?? (destination ? `Network map with destination ${destination.name}` : 'Network map')}>
      <defs>
        <marker id="pg-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5 0 10z" className="pg-arrow" /></marker>
      </defs>
      <path className="pg-land" d={outlinePath(US_OUTLINE, MAIN, US_BOUNDS)} />
      <g className="pg-inset">
        <rect x={INSET.x - 6} y={INSET.y - 6} width={INSET.w + 12} height={INSET.h + 12} rx={6} />
        <path className="pg-land" d={outlinePath(OAHU_OUTLINE, INSET, HI_BOUNDS)} />
        <text x={INSET.x - 2} y={INSET.y + INSET.h + 2} className="pg-map-lbl">HI</text>
      </g>
      {dest && nodes.filter((n) => chosenSet.has(n.id)).map((n) => {
        const from = place(n.lat, n.lon, MAIN, INSET);
        if (!from) return null;
        if (pickup && n.id === chosen[0] && Math.hypot(from.p.x - dest.p.x, from.p.y - dest.p.y) < 14) return null;
        return <path key={n.id} className="pg-route" d={arcPath(from.p, dest.p)} markerEnd="url(#pg-arrow)" />;
      })}
      {nodes.map((n) => {
        const at = place(n.lat, n.lon, MAIN, INSET);
        if (!at) return null;
        const isChosen = chosenSet.has(n.id);
        const r = n.type === 'dc' ? 6 : n.type === 'store' ? 4.5 : 5;
        return (
          <g key={n.id} className={['pg-node', `t-${n.type}`, isChosen && 'chosen', n.active === false && 'inactive'].filter(Boolean).join(' ')} transform={`translate(${at.p.x.toFixed(1)} ${at.p.y.toFixed(1)})`}>
            <title>{`${n.name} (${n.id}) · ${n.type}`}</title>
            {isChosen && <circle className="pg-halo" r={r + 7} />}
            {n.type === 'dc' ? <rect x={-r} y={-r} width={r * 2} height={r * 2} rx={1.5} /> : <circle r={r} />}
            {(isChosen || n.type === 'dc') && <text y={-r - 4} textAnchor="middle" className="pg-node-lbl">{n.id}</text>}
          </g>
        );
      })}
      {dest && (
        <g className="pg-dest" transform={`translate(${dest.p.x.toFixed(1)} ${dest.p.y.toFixed(1)})`}>
          <title>{destination!.name}</title>
          <path d="M0 0 C-7 -8 -7 -16 0 -18 C7 -16 7 -8 0 0 Z" />
          <circle cy={-12.5} r={3} className="pg-dest-dot" />
          <text y={12} textAnchor="middle" className="pg-dest-lbl">{destination!.name}</text>
        </g>
      )}
      <g className="pg-legend" transform={`translate(${VIEW.w - 170} ${VIEW.h - 26})`}>
        <rect x={-4} y={-13} width={8} height={8} rx={1.5} className="t-dc" /><text x={10} y={-6}>DC</text>
        <circle cx={46} cy={-9} r={4} className="t-store" /><text x={56} y={-6}>Store</text>
        <circle cx={100} cy={-9} r={4} className="t-3pl" /><text x={110} y={-6}>3PL / vendor</text>
      </g>
    </svg>
  );
}
