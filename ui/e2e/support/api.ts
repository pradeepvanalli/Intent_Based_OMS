import fs from 'node:fs';
import { request, type APIRequestContext } from '@playwright/test';
import { BASE_URL } from '../playwright.config';
import { tokenPath, type Role } from './roles';

/** An API client authenticated as a role, reusing the token from global setup. */
export async function apiAs(role: Role): Promise<APIRequestContext> {
  const { token } = JSON.parse(fs.readFileSync(tokenPath(role), 'utf8')) as { token: string };
  return request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}
