import { describe, expect, it } from 'vitest';
import { friendlyError, looksTechnical } from './friendly';

describe('friendlyError', () => {
  it('hides framework text behind a plain message and keeps it as technical detail', () => {
    const f = friendlyError({ status: 400, title: 'Bad Request', detail: 'Could not resolve type id \'ThresholdChange\' as a subtype of `ai.intentoms.learning.model.PolicyChange`' });
    expect(f.title).toBe('Some details need fixing');
    expect(f.message).not.toMatch(/ai\.intentoms|type id/);
    expect(f.technical).toMatch(/type id/);
  });

  it('keeps a human-written conflict reason', () => {
    const f = friendlyError({ status: 409, title: 'conflict', detail: 'Cannot promote: inside a tenant freeze window' });
    expect(f.message).toBe('Cannot promote: inside a tenant freeze window');
  });

  it('explains a change that is cooling down after a rollback', () => {
    const short = friendlyError({ status: 409, code: 'proposal_cooling_down', detail: 'The same change to decision:fulfilment_intent was rolled back on 2026-10-01 (late deliveries); it cannot be proposed again for 30 days without different evidence', cooldownDays: 30 });
    expect(short.title).toBe('Rolled back recently');
    expect(short.message).toContain('rolled back on 2026-10-01');
    const long = friendlyError({ status: 409, code: 'proposal_cooling_down', detail: `The same change was rolled back (${'x'.repeat(240)})`, cooldownDays: 30 });
    expect(long.message).toBe("The same change was rolled back recently, so it can't be proposed again for 30 days without different evidence.");
  });

  it('names the missing permission', () => {
    expect(friendlyError({ status: 403, detail: 'Missing permission learning.admin' }).message).toContain('learning.admin');
  });

  it('explains network failures and server errors without jargon', () => {
    expect(friendlyError({ status: 0, title: 'network_error', detail: 'Failed to fetch' }).title).toBe("Can't reach the server");
    expect(friendlyError({ status: 500, detail: 'java.lang.NullPointerException at Foo.bar(Foo.java:12)' }).message).not.toMatch(/java/);
  });

  it('detects technical text', () => {
    expect(looksTechnical('org.springframework.dao.DataIntegrityViolationException')).toBe(true);
    expect(looksTechnical('The author of a proposal cannot approve it themselves')).toBe(false);
  });
});
