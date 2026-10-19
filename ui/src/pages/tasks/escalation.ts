import { humanize } from '../../lib/format';

/** Escalation options arrive as {key: description} (or, from older tasks, a list of keys). */
export function escalationChoices(raw: unknown): [string, string][] {
  if (Array.isArray(raw)) return raw.map((k) => [String(k), humanize(String(k))]);
  if (raw && typeof raw === 'object') return Object.entries(raw as Record<string, unknown>).map(([k, v]) => [k, typeof v === 'string' ? v : humanize(k)]);
  return [['accept', 'Act on the recommendation'], ['override', 'Choose another value']];
}
