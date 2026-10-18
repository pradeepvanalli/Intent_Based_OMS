import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Demo users seeded on every tenant (password equals the user id). */
export const ROLES = {
  admin: 'admin',
  ops: 'ops',
  cs: 'cs',
  risk: 'risk',
  finance: 'finance',
  strategy: 'strategy',
} as const;
export type Role = keyof typeof ROLES;

export const TENANT = process.env.E2E_TENANT ?? 'demo';
export const STATE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '.auth');
export const statePath = (role: Role): string => path.join(STATE_DIR, `${role}.json`);
export const tokenPath = (role: Role): string => path.join(STATE_DIR, `${role}.token.json`);
