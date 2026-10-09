import QRCode from 'qrcode';
import { useEffect, useRef, useState } from 'react';
import { Button } from '../../ui/Button';
import { Dialog, Sheet } from '../../ui/Overlays';

/**
 * End-to-end build only (route /__smoke, absent from the normal build). It exercises, under the REAL strict CSP, the pieces that are
 * most likely to need it loosened: the native <dialog> overlays, the qrcode drawing and the QR decoding in our own worker (jsQR).
 */
const PAYLOAD = 'https://app.example.test/qr#t=abcDEF0123456789_-xyz';

export function SmokePage() {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [dialog, setDialog] = useState(false);
  const [sheet, setSheet] = useState(false);
  const [jsqr, setJsqr] = useState('');

  useEffect(() => {
    if (canvas.current) void QRCode.toCanvas(canvas.current, PAYLOAD, { width: 280, margin: 2 });
  }, []);

  function viaJsQr() {
    const c = canvas.current!;
    const image = c.getContext('2d')!.getImageData(0, 0, c.width, c.height);
    const worker = new Worker(new URL('./jsqr.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent<string | null>) => {
      setJsqr(e.data === null ? 'ERROR no code' : `OK ${e.data}`);
      worker.terminate();
    };
    worker.onerror = () => setJsqr('ERROR worker');
    worker.postMessage({ width: image.width, height: image.height, data: image.data });
  }

  return (
    <main className="mx-auto flex max-w-md flex-col gap-4 p-4">
      <h1 className="font-display text-2xl font-bold">Smoke CSP</h1>
      <p id="barcode-detector">{'BarcodeDetector' in window ? 'barcode-detector: yes' : 'barcode-detector: no'}</p>
      <div className="flex gap-3">
        <Button onClick={() => setDialog(true)}>Abrir diálogo</Button>
        <Button onClick={() => setSheet(true)}>Abrir hoja</Button>
      </div>
      <Dialog open={dialog} onOpenChange={setDialog} title="Confirmar marca" description="Descuenta 1 clase del plan.">
        <Button onClick={() => setDialog(false)}>Entendido</Button>
      </Dialog>
      <Sheet open={sheet} onOpenChange={setSheet} title="Marcar asistencia">
        <Button onClick={() => setSheet(false)}>Listo</Button>
      </Sheet>
      <canvas ref={canvas} aria-label="Código QR de prueba" />
      <Button onClick={viaJsQr}>Decodificar con jsQR en worker propio</Button>
      <output id="jsqr-result">{jsqr}</output>
    </main>
  );
}
