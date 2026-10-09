import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConsentText } from './ConsentText';

function text(bodyMarkdown: string) {
  return { type: 'DATA_ADULT' as const, version: 'v1', title: 'Autorización', bodyMarkdown, required: true, draft: true };
}

describe('ConsentText renders the authorization as plain formatting only', () => {
  it('shows raw HTML as text instead of creating elements', () => {
    const { container } = render(<ConsentText text={text('Hola <script>alert(1)</script> <b>negrita</b> <img src=x onerror=alert(1)>')} />);
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
  });

  it('drops images and does not turn links into anchors', () => {
    const { container } = render(<ConsentText text={text('[política](https://example.test/x) ![logo](https://example.test/l.png)')} />);
    expect(container.querySelector('a')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText(/política/)).toBeInTheDocument();
  });

  it('keeps lists and emphasis, and the region has a name and takes the keyboard focus', () => {
    render(<ConsentText text={text('- uno\n- **dos**')} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    const region = screen.getByRole('region', { name: 'Texto: Autorización' });
    expect(region).toHaveAttribute('tabindex', '0');
  });
});
