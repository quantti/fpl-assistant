// @vitest-environment jsdom

import React, { act, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import type { Route } from './router'

let route: Route = '/'
let mounts = 0
let unmounts = 0

vi.mock('./auth/useSession', () => ({
  useSession: () => ({
    loading: false,
    session: { user: { id: 'user-a' } },
  }),
}))
vi.mock('./hooks/useKeyboardAwareViewport', () => ({ useKeyboardAwareViewport: vi.fn() }))
vi.mock('./router', () => ({ useRoute: () => route }))
vi.mock('./components/SignIn/SignIn', () => ({ default: () => null }))
vi.mock('./shortlist/ShortlistContext', () => ({
  ShortlistProvider: ({ children }: { children: React.ReactNode }) => children,
}))
vi.mock('./components/ChatView/ChatView', () => ({
  default: ({ route: nextRoute }: { route: Route }) => {
    useEffect(() => {
      mounts++
      return () => { unmounts++ }
    }, [])
    return React.createElement('span', null, nextRoute)
  },
}))
import App from './App'

let root: ReturnType<typeof createRoot> | undefined

beforeAll(() => {
  ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  root = undefined
  document.body.replaceChildren()
  route = '/'
  mounts = 0
  unmounts = 0
})

it('keeps the chat shell mounted while navigating to and from pages', () => {
  const host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)

  act(() => root!.render(React.createElement(App)))
  route = '/shortlist'
  act(() => root!.render(React.createElement(App)))
  route = '/my-team'
  act(() => root!.render(React.createElement(App)))

  expect(host.textContent).toBe('/my-team')
  expect(mounts).toBe(1)
  expect(unmounts).toBe(0)
})
