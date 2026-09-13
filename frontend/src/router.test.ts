import { describe, expect, it } from 'vitest'
import { routeFromPath } from './router'

describe('client routes', () => {
  it('recognizes the shortlist deep link', () => {
    expect(routeFromPath('/shortlist')).toBe('/shortlist')
  })

  it('keeps unknown paths on chat', () => {
    expect(routeFromPath('/not-a-page')).toBe('/')
  })
})
