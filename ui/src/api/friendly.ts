import type { ProblemDetail } from './types';

/** What a person sees for a failed request: a plain title, one helpful sentence, and the raw details kept aside. */
export interface FriendlyError { title: string; message: string; technical: string; }

/** Text that reads like a stack trace or framework message rather than something written for a person. */
const TECHNICAL = /exception|java\.|org\.|com\.fasterxml|springframework|sql|jdbc|deserializ|json parse|unrecognized field|nullpointer|\bnull\b|\bat [\w$.]+\(|could not resolve|type id|no static resource|failed to convert/i;

export function looksTechnical(text: string | undefined): boolean {
  if (!text) return false;
  return text.length > 220 || TECHNICAL.test(text);
}

function permissionFrom(detail: string | undefined): string | undefined {
  const m = /permission[s]?\s*[:'"]?\s*([a-z]+\.[a-z*_.]+)/i.exec(detail ?? '');
  return m?.[1];
}

export function friendlyError(p: ProblemDetail): FriendlyError {
  const detail = typeof p.detail === 'string' ? p.detail : undefined;
  const human = detail && !looksTechnical(detail) ? detail : undefined;
  const technical = [`HTTP ${p.status}`, p.code ?? p.title, detail, p.instance].filter(Boolean).join(' · ');
  const fieldList = p.fields ? Object.entries(p.fields).map(([k, v]) => `${k}: ${v}`).join('; ') : undefined;
  switch (true) {
    case p.status === 0:
      return { title: "Can't reach the server", message: 'Check your connection or whether the OMS is running, then try again.', technical };
    case p.status === 401:
      return { title: 'Your session has ended', message: 'Sign in again to continue.', technical };
    case p.status === 403: {
      const perm = permissionFrom(detail);
      return { title: "You don't have access to this", message: perm ? `This needs the “${perm}” permission. Ask an admin to grant it.` : 'Ask an admin for access.', technical };
    }
    case p.status === 404:
      return { title: 'Not found', message: human ?? 'It may have been removed, or the link is out of date.', technical };
    case p.code === 'proposal_cooling_down': {
      // Defect 26: the same change was rolled back recently; say so in words even when the server's sentence is long.
      const days = typeof p.cooldownDays === 'number' ? p.cooldownDays : undefined;
      return { title: 'Rolled back recently', message: human ?? `The same change was rolled back recently, so it can't be proposed again${days ? ` for ${days} days` : ''} without different evidence.`, technical };
    }
    case p.status === 409:
      return { title: 'This could not be done right now', message: human ?? 'Something changed while you were working. Refresh and try again.', technical };
    case p.status === 429:
      return { title: 'Too many requests', message: 'Wait a few seconds and try again.', technical };
    case p.status === 400 || p.status === 422:
      return { title: 'Some details need fixing', message: fieldList ?? human ?? 'Check the values you entered and try again.', technical };
    case p.status >= 500:
      return { title: 'Something went wrong on our side', message: 'Try again. If it keeps happening, share the technical details below with your support team.', technical };
    default:
      return { title: 'Request failed', message: human ?? 'Try again in a moment.', technical };
  }
}
