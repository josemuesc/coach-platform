import { useRef, useState } from 'react';
import { formatDayMonthTime, whatsappLink } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';

interface Props {
  /** What the link is for, e.g. "Enlace de invitación". */
  label: string;
  url: string;
  expiresAt?: string;
  /** When given, offers "Enviar por WhatsApp": a normal link that opens WhatsApp with this message written. Nothing is sent by itself. */
  whatsapp?: { phone: string | null | undefined; message: string };
  /** Shown under the link (e.g. the warning that whoever has it can enter as the student). */
  note?: string;
}

/** A link shown ONCE (the server keeps only its hash) with the two ways to hand it over: copy it, or open WhatsApp with the message. */
export function ShareLink({ label, url, expiresAt, whatsapp, note }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied('yes');
    } catch {
      input.current?.select();
      setCopied('no');
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-2xl bg-white p-4">
      <label htmlFor="share-link" className="text-sm font-semibold">
        {label}
      </label>
      <input
        id="share-link"
        ref={input}
        readOnly
        value={url}
        onFocus={(e) => e.currentTarget.select()}
        className="min-h-11 w-full rounded-xl border border-line bg-bg px-3 text-sm"
      />
      {expiresAt && <p className="text-sm text-ink-2">Vale hasta el {formatDayMonthTime(expiresAt)}. Se muestra una sola vez.</p>}
      <div className="flex flex-wrap gap-3">
        <Button onClick={() => void copy()}>Copiar</Button>
        {whatsapp && (
          <a
            href={whatsappLink(whatsapp.phone, whatsapp.message)}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex min-h-11 items-center justify-center rounded-xl border border-line bg-white px-4 font-semibold"
          >
            Enviar por WhatsApp
          </a>
        )}
      </div>
      <div aria-live="polite">
        {copied === 'yes' && <Banner tone="green">Enlace copiado.</Banner>}
        {copied === 'no' && <Banner tone="amber">No se pudo copiar solo: el enlace quedó seleccionado, cópialo a mano.</Banner>}
      </div>
      {note && <Banner tone="amber">{note}</Banner>}
    </div>
  );
}
