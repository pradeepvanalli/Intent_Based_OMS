import { Button } from '../components/Button';
import { useTenantName } from './tenantName';

/** Topbar tenant chip with display name and a "Switch tenant" action (sign out → login page). */
export function TenantBadge({ onSwitch }: { onSwitch: () => void }) {
  const { id, name } = useTenantName();
  return (
    <>
      <span className="tenant" title={id && id !== name ? `Tenant ${id}` : 'Tenant'}>⌂ {name}</span>
      <Button variant="ghost" size="sm" onClick={onSwitch} title="Sign out and pick another tenant on the login page">Switch tenant</Button>
    </>
  );
}
