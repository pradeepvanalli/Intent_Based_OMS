import fs from 'node:fs';
import { request, type FullConfig } from '@playwright/test';
import { ROLES, STATE_DIR, TENANT, statePath, tokenPath, type Role } from './roles';

/**
 * Logs in once per role through the API (login is rate limited, so specs reuse these) and writes a storage state
 * the UI understands: the session token and principal in localStorage, and the first-run tour marked as seen so it
 * never covers the page under test.
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0].use.baseURL as string;
  fs.mkdirSync(STATE_DIR, { recursive: true });
  const ctx = await request.newContext({ baseURL });
  for (const role of Object.keys(ROLES) as Role[]) {
    const user = ROLES[role];
    const res = await ctx.post('/api/v1/auth/login', { data: { tenantId: TENANT, userId: user, password: user } });
    if (!res.ok()) throw new Error(`Login for ${user} failed: HTTP ${res.status()} ${await res.text()}`);
    const { token, principal } = (await res.json()) as { token: string; principal: unknown };
    fs.writeFileSync(tokenPath(role), JSON.stringify({ token, principal }));
    fs.writeFileSync(statePath(role), JSON.stringify({
      cookies: [],
      origins: [{
        origin: new URL(baseURL).origin,
        localStorage: [
          { name: 'oms.token', value: token },
          { name: 'oms.principal', value: JSON.stringify(principal) },
          { name: `oms.tour.seen.${TENANT}`, value: '1' },
        ],
      }],
    }));
  }
  await ctx.dispose();
}
