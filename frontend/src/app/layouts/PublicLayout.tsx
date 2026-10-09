import { Outlet } from 'react-router';
import { BrandMark } from '../../ui/BrandMark';

export function PublicLayout() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-6 p-6">
      <div className="flex items-center gap-3">
        <BrandMark name="Coach" />
        <span className="font-display text-xl font-bold">Coach</span>
      </div>
      <Outlet />
    </main>
  );
}
