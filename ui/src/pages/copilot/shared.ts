import type { ProposedChange } from '../../api/types';

/** Any one of these lets a user open the copilot and stage drafts. */
export const COPILOT_PERMISSIONS = ['decision.edit', 'statemodel.edit', 'config.admin'];

/** Permission needed to apply a staged artefact of a given kind. */
export function applyPermission(kind: string): string {
  return kind === 'state_model' || kind === 'order_type' || kind === 'sla' ? 'statemodel.edit' : 'config.admin';
}

/** Where a staged change went: a studio draft (name@version) or an artefact in the Artifacts tab. */
export function stagedLink(c: ProposedChange): { to: string; label: string } | null {
  if (!c.staged) return null;
  if (c.staged.kind === 'decision_draft') { const [name, v] = c.staged.ref.split('@'); return { to: `/studio/${encodeURIComponent(name)}`, label: `Studio draft ${name} v${v}` }; }
  return { to: `/copilot?tab=artifacts&artifact=${encodeURIComponent(c.staged.ref)}`, label: `Artifact ${c.staged.ref}` };
}
