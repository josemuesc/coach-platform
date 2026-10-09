import { Link, Outlet } from 'react-router';
import { formatDateTime } from '../../lib/time';
import { useSession } from '../../session/SessionProvider';
import { Banner } from '../../ui/Banner';
import { BrandMark } from '../../ui/BrandMark';
import { HistoryIcon, HomeIcon, TodayIcon, UserIcon } from '../../ui/icons';
import { BottomNav, type NavItem } from './BottomNav';

const ITEMS: NavItem[] = [
  { to: '/app', label: 'Inicio', icon: <HomeIcon />, end: true },
  { to: '/app/book', label: 'Agendar', icon: <TodayIcon /> },
  { to: '/app/history', label: 'Historial', icon: <HistoryIcon /> },
  { to: '/app/account', label: 'Cuenta', icon: <UserIcon /> },
];

/**
 * Shown for as long as the SERVER says the last password change was made through a reset link the coach handed over
 * (passwordChangedBy = COACH_LINK). It goes away only when the person changes the password themselves.
 */
export function PasswordResetNotice() {
  const { me } = useSession();
  if (me?.passwordChangedBy !== 'COACH_LINK') return null;
  return (
    <div className="px-4 pt-3">
      <Banner tone="amber" role="status">
        La contraseña de esta cuenta se restableció con un enlace de tu entrenador
        {me.passwordChangedAt ? ` el ${formatDateTime(me.passwordChangedAt)}` : ''}. Si no fuiste tú, cámbiala ahora desde{' '}
        <Link to="/app/account" className="underline">
          Cuenta
        </Link>
        .
      </Banner>
    </div>
  );
}

export function StudentLayout() {
  const { me } = useSession();
  return (
    <div className="mx-auto min-h-dvh max-w-md pb-20">
      <header className="flex items-center gap-3 px-4 pt-4">
        <BrandMark name={me?.brandName} />
        <span className="font-display text-lg font-bold">{me?.brandName}</span>
      </header>
      <PasswordResetNotice />
      <main>
        <Outlet />
      </main>
      <BottomNav items={ITEMS} label="Secciones del alumno" />
    </div>
  );
}

export { StudentLayout as Component };
