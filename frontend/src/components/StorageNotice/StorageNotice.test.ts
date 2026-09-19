// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import StorageNotice, { STORAGE_NOTICE_KEY } from './StorageNotice';

let root: ReturnType<typeof createRoot> | undefined;

beforeAll(() => {
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', true);
});

beforeEach(() => globalThis.sessionStorage.clear());

afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
  vi.restoreAllMocks();
});

function mount(): HTMLDivElement {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(StorageNotice)));
  return host;
}

it('explains necessary browser storage without presenting consent controls', () => {
  const host = mount();

  expect(host.textContent).toContain('keep you signed in and remember your theme');
  expect(host.textContent).toContain('no optional analytics or advertising trackers');
  expect(host.querySelector('a')?.getAttribute('href')).toBe('/privacy');
  expect([...host.querySelectorAll('button')].map((button) => button.textContent)).toEqual([
    'Got it',
  ]);
});

it('acknowledges the notice only for the browser tab', () => {
  const host = mount();
  act(() => host.querySelector('button')!.click());

  expect(globalThis.sessionStorage.getItem(STORAGE_NOTICE_KEY)).toBe('1');
  expect(host.textContent).toBe('');

  act(() => root!.unmount());
  root = undefined;
  expect(mount().textContent).toBe('');
});

it('can be dismissed when browser storage is blocked and returns after reload', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('blocked');
  });

  const host = mount();
  act(() => host.querySelector('button')!.click());
  expect(host.textContent).toBe('');

  act(() => root!.unmount());
  root = undefined;
  expect(mount().textContent).toContain('keep you signed in');
});
