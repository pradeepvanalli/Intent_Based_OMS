import type { LiveFamilyDomain } from '../../api/types';

const fmt = (v: number) => String(+v.toFixed(6));

/**
 * B-1: the plain-language problems with a live-use range for a learned family, the same checks the server makes
 * (LiveFamilies.validate): inside the family's values, a max step of at most half of them, nothing for an on/off switch.
 */
export function rangeProblems(d: LiveFamilyDomain | undefined, min?: number | null, max?: number | null, maxStep?: number | null): string[] {
  if (!d) return [];
  const out: string[] = [];
  const has = (v?: number | null): v is number => v != null && !Number.isNaN(v);
  if (d.kind === 'SWITCH') {
    if (has(min) || has(max) || has(maxStep)) out.push(`${d.family} is ${d.description}; leave min, max and max step empty`);
    return out;
  }
  if (has(min) && has(max) && min > max) out.push(`min ${fmt(min)} is above max ${fmt(max)}`);
  if (has(maxStep) && maxStep <= 0) out.push('max step must be above 0');
  if (d.kind === 'TABLE' || d.min == null || d.max == null) return out;
  if (has(min) && (min < d.min || min > d.max)) out.push(`min ${fmt(min)} is outside the values ${d.family} can take: ${d.description}`);
  if (has(max) && (max < d.min || max > d.max)) out.push(`max ${fmt(max)} is outside the values ${d.family} can take: ${d.description}`);
  if (has(maxStep) && d.maxStepLimit != null && maxStep > d.maxStepLimit) {
    out.push(`max step ${fmt(maxStep)} is too large for ${d.family}: one promotion may move it at most ${fmt(d.maxStepLimit)} (half of ${fmt(d.min)} to ${fmt(d.max)})`);
  }
  return out;
}
