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
  // the invitation draws its own header: it wears the coach's brand, not the generic one
  { path: '/invite/:token', lazy: () => import('../features/invite/InvitePage').then((m) => ({ Component: m.InvitePage })) },
  { path: '/invite', lazy: () => import('../features/invite/InvitePage').then((m) => ({ Component: m.InvitePage })) },
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
      // full screen, no navigation bar: the coach shows the phone to the class
      { path: '/coach/qr/:eventId', lazy: () => import('../features/coach/qr/QrPage').then((m) => ({ Component: m.QrPage })) },
      {
        path: '/coach',
        // each area is its own chunk: a student never downloads the coach's code
        lazy: () => import('./layouts/CoachLayout'),
        children: [
          { index: true, lazy: () => import('../features/coach/today/TodayPage').then((m) => ({ Component: m.TodayPage })) },
          { path: 'students', lazy: () => import('../features/coach/students/StudentsPage').then((m) => ({ Component: m.StudentsPage })) },
          { path: 'students/new', lazy: () => import('../features/coach/students/NewStudentPage').then((m) => ({ Component: m.NewStudentPage })) },
          { path: 'students/:id', lazy: () => import('../features/coach/students/StudentProfilePage').then((m) => ({ Component: m.StudentProfilePage })) },
          { path: 'agenda', lazy: () => import('../features/coach/agenda/AgendaPage').then((m) => ({ Component: m.AgendaPage })) },
          { path: 'settings', lazy: () => import('../features/coach/settings/SettingsPage').then((m) => ({ Component: m.SettingsPage })) },
          { path: 'plans', lazy: () => import('../features/coach/settings/PlansPage').then((m) => ({ Component: m.PlansPage })) },
          { path: 'availability', lazy: () => import('../features/coach/settings/AvailabilityPage').then((m) => ({ Component: m.AvailabilityPage })) },
          { path: 'account', element: <ChangePasswordPage /> },
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
