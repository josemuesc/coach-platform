import type { ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router';

export interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  /** Other places that belong to this section (e.g. the pages that hang from Ajustes): the item stays highlighted there. */
  alsoActiveOn?: string[];
}

export function BottomNav({ items, label }: { items: NavItem[]; label: string }) {
  const { pathname } = useLocation();
  return (
    <nav aria-label={label} className="fixed inset-x-0 bottom-0 border-t border-line bg-white pb-[env(safe-area-inset-bottom)]">
      <ul className="mx-auto flex max-w-md">
        {items.map((item) => (
          <li key={item.to} className="flex-1">
            <NavLink
              to={item.to}
              end={item.end ?? false}
              className={({ isActive }) =>
                `flex min-h-14 flex-col items-center justify-center gap-0.5 text-xs font-semibold ${
                  isActive || item.alsoActiveOn?.some((p) => pathname === p || pathname.startsWith(`${p}/`)) ? 'text-brand-ink' : 'text-ink-2'
                }`
              }
            >
              {item.icon}
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
