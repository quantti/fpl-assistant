import { describe, expect, it } from 'vitest'
import { keyboardAwareHeight, KEYBOARD_MIN_INSET } from './viewportHeight'

const window = 844 // iPhone 14 CSS px

describe('keyboardAwareHeight', () => {
  it('leaves the layout viewport in charge when nothing is covering it', () => {
    expect(keyboardAwareHeight({ height: 844, offsetTop: 0, scale: 1 }, window)).toBeNull()
  })

  it('sizes the shell to the visual viewport when a keyboard-sized inset appears', () => {
    // iOS keyboard on a 14: roughly 336px
    expect(keyboardAwareHeight({ height: 508, offsetTop: 0, scale: 1 }, window)).toBe(508)
  })

  it('ignores insets smaller than a keyboard, such as the URL bar collapsing', () => {
    expect(keyboardAwareHeight({ height: window - KEYBOARD_MIN_INSET + 1, offsetTop: 0, scale: 1 }, window))
      .toBeNull()
  })

  it('ignores a pinch-zoom, which also shrinks the visual viewport', () => {
    expect(keyboardAwareHeight({ height: 400, offsetTop: 120, scale: 2.1 }, window)).toBeNull()
  })

  it('treats the threshold itself as a keyboard', () => {
    expect(keyboardAwareHeight({ height: window - KEYBOARD_MIN_INSET, offsetTop: 0, scale: 1 }, window))
      .toBe(window - KEYBOARD_MIN_INSET)
  })
})
