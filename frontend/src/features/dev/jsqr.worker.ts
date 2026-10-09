import jsQR from 'jsqr';

// Candidate engine for the smoke test: pure JavaScript, bundled as our OWN worker file (so `worker-src 'self'` is enough).
const scope = self as unknown as {
  onmessage: ((e: MessageEvent<{ width: number; height: number; data: Uint8ClampedArray }>) => void) | null;
  postMessage: (message: string | null) => void;
};

scope.onmessage = (e) => {
  const result = jsQR(e.data.data, e.data.width, e.data.height);
  scope.postMessage(result ? result.data : null);
};
