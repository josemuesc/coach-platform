import QRCode from 'qrcode';
import QrScanner from 'qr-scanner';
import { useEffect, useRef, useState } from 'react';
import { Button } from '../../ui/Button';
import { Dialog, Sheet } from '../../ui/Overlays';
import { RadixEvidence } from './RadixEvidence';

/**
 * End-to-end build only (route /__smoke, absent from the normal build). It exercises, under the REAL strict CSP, the pieces that are
 * most likely to need it loosened: Radix Dialog / Sheet (inject <style>?), the qrcode drawing, and the two QR decoding engines in a
 * browser with no BarcodeDetector (what iOS Safari is).
 */
const PAYLOAD = 'https://app.example.test/qr#t=abcDEF0123456789_-xyz';

export function SmokePage() {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [dialog, setDialog] = useState(false);
  const [sheet, setSheet] = useState(false);
  const [scanner, setScanner] = useState('');
  const [jsqr, setJsqr] = useState('');

  useEffect(() => {
    if (canvas.current) void QRCode.toCanvas(canvas.current, PAYLOAD, { width: 280, margin: 2 });
  }, []);

  async function viaQrScanner() {
    try {
      // emulate a browser without BarcodeDetector (iOS Safari): qr-scanner must then decode in its own worker
      (QrScanner as unknown as { _disableBarcodeDetector: boolean })._disableBarcodeDetector = true;
      const result = await QrScanner.scanImage(canvas.current!, { returnDetailedScanResult: true });
      setScanner(`OK ${result.data}`);
    } catch (e) {
      setScanner(`ERROR ${String(e)}`);
    }
  }

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
      <RadixEvidence />
      <canvas ref={canvas} aria-label="Código QR de prueba" />
      <Button onClick={() => void viaQrScanner()}>Decodificar con qr-scanner</Button>
      <output id="qr-scanner-result">{scanner}</output>
      <Button onClick={viaJsQr}>Decodificar con jsQR en worker propio</Button>
      <output id="jsqr-result">{jsqr}</output>
    </main>
  );
}
