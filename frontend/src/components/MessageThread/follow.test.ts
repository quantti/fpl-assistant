import { describe, expect, it } from 'vitest';
import { followsBottom, FOLLOW_TOLERANCE } from './follow';

describe('followsBottom', () => {
  it('follows when the reader is at the very bottom', () => {
    expect(followsBottom({ scrollHeight: 2000, scrollTop: 1400, clientHeight: 600 })).toBe(true);
  });

  it('follows when the thread has no scrollbar at all', () => {
    expect(followsBottom({ scrollHeight: 300, scrollTop: 0, clientHeight: 600 })).toBe(true);
  });

  it('still follows a hair above the bottom, where a smooth scroll leaves you', () => {
    expect(
      followsBottom({
        scrollHeight: 2000,
        scrollTop: 1400 - FOLLOW_TOLERANCE + 1,
        clientHeight: 600,
      }),
    ).toBe(true);
  });

  it('stops following once the reader has scrolled up to read', () => {
    expect(followsBottom({ scrollHeight: 2000, scrollTop: 900, clientHeight: 600 })).toBe(false);
  });

  it('treats exactly the tolerance as scrolled away', () => {
    expect(
      followsBottom({ scrollHeight: 2000, scrollTop: 1400 - FOLLOW_TOLERANCE, clientHeight: 600 }),
    ).toBe(false);
  });
});
