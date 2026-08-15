import { createTheme, type MantineColorsTuple } from '@mantine/core'

/**
 * Mantine v9 theme, wired to the same custom properties defined in
 * src/styles/tokens.css so Mantine components never carry a second,
 * divergent palette.
 *
 * The design palette (docs/superpowers/specs/2026-08-15-frontend-design-system.md)
 * has one flat colour per role rather than Mantine's usual 10-shade ramp, so
 * each tuple below repeats the same `var(--token)` reference across all 10
 * indices. Mantine accepts `var()` strings as colour values directly and
 * passes them through to CSS untouched — confirmed against the installed
 * v9.5.1 source (see luminance.mjs's `isLightColor`, which special-cases
 * `color.startsWith('var(')`, and default-css-variables-resolver.mjs, which
 * assigns `theme.colors[color][index]` straight into a CSS custom property
 * with no parsing). The one caveat: Mantine's autoContrast heuristic can't
 * compute the actual lightness of a var() reference, so it treats every
 * var()-based colour as "not light" — irrelevant here since autoContrast is
 * off (the default) and no component consumes these colours yet.
 */

const asTuple = (varName: string): MantineColorsTuple => {
  const value = `var(${varName})`
  return [value, value, value, value, value, value, value, value, value, value]
}

export const theme = createTheme({
  primaryColor: 'pitch',
  colors: {
    pitch: asTuple('--pitch'),
    card: asTuple('--card'),
    redcard: asTuple('--redcard'),
    flood: asTuple('--flood'),
    kit: asTuple('--kit'),
  },
  black: 'var(--ink)',
  white: 'var(--white)',
  fontFamily: 'var(--font-ui)',
  headings: {
    fontFamily: 'var(--font-ui)',
  },
})
