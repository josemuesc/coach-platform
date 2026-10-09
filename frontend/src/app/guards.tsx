import { Navigate, Outlet, useLocation } from 'react-router';
import { homeFor, useSession } from '../session/SessionProvider';
import { ErrorState, Skeleton } from '../ui/States';

/**
 * Sends each person to their own area. This is convenience only: every endpoint is protected by the server, which decides
 * what each role may do.
 */
export function RequireRole({ role }: { role: 'COACH' | 'STUDENT' }) {
  const { status, me, retry } = useSession();
  const location = useLocation();
  if (status === 'anonymous') {
    return <Navigate to={`/login?redirect=${encodeURIComponent(location.pathname)}`} replace />;
  }
  if (status === 'loading') return <Skeleton label="Comprobando tu sesión" />;
  if (status === 'error') return <ErrorState error={null} onRetry={retry} />;
  if (me?.role !== role) return <Navigate to={homeFor(me?.role)} replace />;
  return <Outlet />;
}

export function RootRedirect() {
  const { status, me } = useSession();
  if (status === 'loading') return <Skeleton />;
  return <Navigate to={status === 'authenticated' ? homeFor(me?.role) : '/login'} replace />;
}
