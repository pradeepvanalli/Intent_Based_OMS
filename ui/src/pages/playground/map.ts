/* Equirectangular projection helpers and a simplified continental-US outline for the Playground network map. Pure. */

export interface Bounds { minLon: number; maxLon: number; minLat: number; maxLat: number; }
export interface Box { x: number; y: number; w: number; h: number; }
export interface Point { x: number; y: number; }

/** Continental US viewport. */
export const US_BOUNDS: Bounds = { minLon: -125.5, maxLon: -66, minLat: 24, maxLat: 50 };
/** Hawaii inset viewport (Honolulu and the main islands). */
export const HI_BOUNDS: Bounds = { minLon: -160.5, maxLon: -154.5, minLat: 18.5, maxLat: 22.6 };

/** Simple equirectangular projection of (lat, lon) into a pixel box. Linear in both axes; no clamping. */
export function project(lat: number, lon: number, box: Box, bounds: Bounds = US_BOUNDS): Point {
  const x = box.x + ((lon - bounds.minLon) / (bounds.maxLon - bounds.minLon)) * box.w;
  const y = box.y + ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat)) * box.h;
  return { x, y };
}

export function inBounds(lat: number, lon: number, bounds: Bounds = US_BOUNDS): boolean {
  return lat >= bounds.minLat && lat <= bounds.maxLat && lon >= bounds.minLon && lon <= bounds.maxLon;
}

/** Projects into the main map when inside it, else into the Hawaii inset when inside that, else null (off map). */
export function place(lat: number, lon: number, main: Box, inset: Box): { p: Point; inset: boolean } | null {
  if (inBounds(lat, lon, US_BOUNDS)) return { p: project(lat, lon, main, US_BOUNDS), inset: false };
  if (inBounds(lat, lon, HI_BOUNDS)) return { p: project(lat, lon, inset, HI_BOUNDS), inset: true };
  return null;
}

/** Great-circle distance in km (haversine) for the plan summary. */
export function distanceKm(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const R = 6371; const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat); const dLon = toRad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Simplified continental-US silhouette as [lon, lat] pairs, clockwise from Cape Flattery. */
export const US_OUTLINE: [number, number][] = [
  [-124.7, 48.4], [-122.8, 49.0], [-95.2, 49.0], [-94.6, 48.7], [-89.6, 48.0], [-88.4, 48.3], [-84.9, 46.9], [-84.6, 46.5], [-83.5, 45.9], [-82.5, 45.3],
  [-82.1, 43.6], [-83.1, 42.1], [-82.4, 41.7], [-78.9, 42.9], [-79.2, 43.5], [-76.8, 43.6], [-74.7, 45.0], [-71.5, 45.0], [-70.0, 46.7], [-67.8, 47.1],
  [-67.0, 44.8], [-70.0, 43.5], [-70.7, 42.0], [-70.0, 41.7], [-72.0, 41.0], [-74.0, 40.6], [-74.0, 39.0], [-75.5, 37.8], [-76.0, 36.9], [-75.7, 35.3],
  [-77.8, 34.0], [-80.9, 32.0], [-81.4, 30.4], [-80.1, 26.8], [-80.4, 25.2], [-81.7, 25.1], [-82.7, 27.9], [-83.0, 29.1], [-84.4, 29.9], [-85.9, 30.2],
  [-88.0, 30.4], [-89.5, 30.2], [-90.1, 29.2], [-91.5, 29.5], [-93.8, 29.7], [-96.5, 28.4], [-97.3, 27.3], [-97.1, 25.9], [-99.1, 26.4], [-100.7, 29.1],
  [-102.0, 29.8], [-103.3, 29.0], [-104.5, 29.6], [-106.5, 31.8], [-108.2, 31.3], [-111.0, 31.3], [-114.8, 32.5], [-117.1, 32.5], [-118.4, 33.8], [-120.6, 34.5],
  [-121.9, 36.6], [-122.5, 37.8], [-124.0, 40.0], [-124.4, 42.0], [-124.1, 44.0], [-124.0, 46.3],
];

/** Oahu, roughly, for the inset. */
export const OAHU_OUTLINE: [number, number][] = [[-158.28, 21.58], [-157.95, 21.71], [-157.65, 21.32], [-157.8, 21.26], [-158.1, 21.3], [-158.28, 21.58]];

/** SVG path for an outline projected into a box. */
export function outlinePath(points: [number, number][], box: Box, bounds: Bounds): string {
  if (!points.length) return '';
  return points.map(([lon, lat], i) => { const p = project(lat, lon, box, bounds); return `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`; }).join(' ') + ' Z';
}

/** Quadratic arc between two points, bulging towards the top (reads as a route on the map). */
export function arcPath(a: Point, b: Point): string {
  const mx = (a.x + b.x) / 2; const my = (a.y + b.y) / 2;
  const d = Math.hypot(b.x - a.x, b.y - a.y);
  const cx = mx; const cy = my - Math.min(60, d * 0.22);
  return `M${a.x.toFixed(1)} ${a.y.toFixed(1)} Q${cx.toFixed(1)} ${cy.toFixed(1)} ${b.x.toFixed(1)} ${b.y.toFixed(1)}`;
}
