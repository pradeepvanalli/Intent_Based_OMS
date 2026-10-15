import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useSession } from './session';

export function RequireAuth() {
  const { token, principal } = useSession();
  const loc = useLocation();
  if (!token || !principal) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  return <Outlet />;
}
