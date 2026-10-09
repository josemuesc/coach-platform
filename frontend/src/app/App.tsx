import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { OfflineBanner, UpdatePrompt } from '../pwa/PwaPrompts';
import { SessionProvider } from '../session/SessionProvider';
import { router } from './router';

// In memory only: nothing the API returns is persisted anywhere (no persistQueryClient, no storage).
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true, staleTime: 15_000 } },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <RouterProvider router={router} />
        <UpdatePrompt />
        <OfflineBanner />
      </SessionProvider>
    </QueryClientProvider>
  );
}
