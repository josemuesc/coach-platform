import { useRegisterSW } from 'virtual:pwa-register/react';
import { useEffect, useState } from 'react';
import { Button } from '../ui/Button';

/** The new version waits until the person agrees: a reload in the middle of marking attendance or scanning a code would lose work. */
export function UpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();
  if (!needRefresh) return null;
  return (
    <div role="status" className="fixed inset-x-3 top-3 z-50 flex items-center gap-3 rounded-2xl bg-ink p-3 text-white shadow-lg">
      <p className="flex-1 text-sm font-semibold">Hay una versión nueva de la aplicación.</p>
      <Button variant="secondary" onClick={() => void updateServiceWorker(true)}>
        Actualizar
      </Button>
      <Button variant="ghost" className="text-white" onClick={() => setNeedRefresh(false)} aria-label="Cerrar aviso">
        Luego
      </Button>
    </div>
  );
}

/** Offline the shell still opens, but nothing personal is stored for offline use: say so instead of showing stale data. */
export function OfflineBanner() {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  if (online) return null;
  return (
    <div role="status" className="fixed inset-x-0 bottom-16 z-40 mx-auto max-w-md px-3">
      <div className="rounded-xl bg-amber-bg px-4 py-3 text-sm font-semibold text-amber-ink">
        Sin conexión: necesitas internet para ver y cambiar tus datos.
      </div>
    </div>
  );
}
