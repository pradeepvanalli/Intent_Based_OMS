import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Demo users (password equals the user id). `platform` is the dev platform engineer (role platform_engineer, signs in
 * on the demo tenant): the only user who may list tenants or switch learning off for every tenant.
 */
export const ROLES = {
  admin: 'admin',
  ops: 'ops',
  cs: 'cs',
  risk: 'risk',
  finance: 'finance',
  strategy: 'strategy',
  platform: 'platform',
} as const;
export type Role = keyof typeof ROLES;

export const TENANT = process.env.E2E_TENANT ?? 'demo';
export const STATE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '.auth');
export const statePath = (role: Role): string => path.join(STATE_DIR, `${role}.json`);
export const tokenPath = (role: Role): string => path.join(STATE_DIR, `${role}.token.json`);
