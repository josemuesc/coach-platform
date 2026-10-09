import Markdown from 'react-markdown';
import type { components } from '../../api/schema';

type ConsentTextView = components['schemas']['ConsentTextView'];

/**
 * One authorization text. react-markdown never renders raw HTML (it shows it as text), and images and links are dropped on top of that:
 * the server already neutralizes the variables, this keeps whatever the text itself contains from reaching the DOM as anything but
 * plain formatting. The box scrolls and can take the keyboard focus (so it can be read without a mouse).
 */
export function ConsentText({ text }: { text: ConsentTextView }) {
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-display text-lg font-bold">{text.title}</h2>
      <div
        role="region"
        aria-label={`Texto: ${text.title}`}
        tabIndex={0}
        className="max-h-64 overflow-y-auto rounded-xl border border-line bg-white p-3 text-sm leading-relaxed text-ink [&_li]:ml-5 [&_li]:list-disc [&_p]:mb-3"
      >
        <Markdown disallowedElements={['img', 'a']} unwrapDisallowed>
          {text.bodyMarkdown}
        </Markdown>
      </div>
    </section>
  );
}
