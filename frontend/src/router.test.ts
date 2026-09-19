import { describe, expect, it } from 'vitest';
import { routeFromPath } from './router';

describe('client routes', () => {
  it.each(['/login', '/register', '/privacy', '/app', '/shortlist', '/admin'] as const)(
    'recognizes the %s deep link',
    (path) => {
      expect(routeFromPath(path)).toBe(path);
    },
  );

  it('keeps unknown paths on the public homepage', () => {
    expect(routeFromPath('/not-a-page')).toBe('/');
  });
});
