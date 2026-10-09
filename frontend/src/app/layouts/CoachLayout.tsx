import { Outlet } from 'react-router';
import { useSession } from '../../session/SessionProvider';
import { BrandMark } from '../../ui/BrandMark';
import { CalendarIcon, SettingsIcon, TodayIcon, UsersIcon } from '../../ui/icons';
import { BottomNav, type NavItem } from './BottomNav';

const ITEMS: NavItem[] = [
  { to: '/coach', label: 'Hoy', icon: <TodayIcon />, end: true },
  { to: '/coach/students', label: 'Alumnos', icon: <UsersIcon /> },
  { to: '/coach/agenda', label: 'Agenda', icon: <CalendarIcon /> },
  { to: '/coach/settings', label: 'Ajustes', icon: <SettingsIcon /> },
];

export function CoachLayout() {
  const { me } = useSession();
  return (
    <div className="mx-auto min-h-dvh max-w-md pb-20">
      <header className="flex items-center gap-3 px-4 pt-4">
        <BrandMark name={me?.brandName} />
        <span className="font-display text-lg font-bold">{me?.brandName}</span>
      </header>
      <main>
        <Outlet />
      </main>
      <BottomNav items={ITEMS} label="Secciones del entrenador" />
    </div>
  );
}

export { CoachLayout as Component };
