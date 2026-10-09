import { createBrowserRouter, type RouteObject } from 'react-router';
import { ChangePasswordPage } from '../features/auth/ChangePasswordPage';
import { ComingSoon } from '../features/auth/ComingSoon';
import { LoginPage } from '../features/auth/LoginPage';
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage';
import { RequireRole, RootRedirect } from './guards';
import { PublicLayout } from './layouts/PublicLayout';

function NotFound() {
  return <ComingSoon title="No encontramos esa página" />;
}

// Only the end-to-end build includes the smoke page; in the normal build this branch is removed and its chunk is never emitted.
const smokeRoutes: RouteObject[] =
  import.meta.env.MODE === 'e2e' ? [{ path: '/__smoke', lazy: () => import('../features/dev/SmokePage').then((m) => ({ Component: m.SmokePage })) }] : [];

export const routes: RouteObject[] = [
  { path: '/', element: <RootRedirect /> },
  {
    element: <PublicLayout />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/reset/:token', element: <ResetPasswordPage /> },
      { path: '/reset', element: <ResetPasswordPage /> },
    ],
  },
  {
    element: <RequireRole role="COACH" />,
    children: [
      {
        path: '/coach',
        // each area is its own chunk: a student never downloads the coach's code
        lazy: () => import('./layouts/CoachLayout'),
        children: [
          { index: true, element: <ComingSoon title="Hoy" /> },
          { path: 'students', element: <ComingSoon title="Alumnos" /> },
          { path: 'agenda', element: <ComingSoon title="Agenda" /> },
          { path: 'settings', element: <ChangePasswordPage /> },
        ],
      },
    ],
  },
  {
    element: <RequireRole role="STUDENT" />,
    children: [
      {
        path: '/app',
        lazy: () => import('./layouts/StudentLayout'),
        children: [
          { index: true, element: <ComingSoon title="Inicio" /> },
          { path: 'book', element: <ComingSoon title="Agendar" /> },
          { path: 'history', element: <ComingSoon title="Historial" /> },
          { path: 'account', element: <ChangePasswordPage /> },
        ],
      },
    ],
  },
  ...smokeRoutes,
  { path: '*', element: <NotFound /> },
];

export const router = createBrowserRouter(routes);
