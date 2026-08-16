import {
  createTheme,
  defaultVariantColorsResolver,
  type CSSVariablesResolver,
  type MantineColorsTuple,
  type VariantColorsResolver,
} from '@mantine/core'

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
 * var()-based colour as "not light". `primaryColor: 'pitch'` IS consumed —
 * by the composer's send button, and by anything filled that Phase 5 adds —
 * so that caveat is load-bearing, not academic: with autoContrast off (the
 * default) Mantine hardcodes the filled-variant label to `theme.white`,
 * which fails against dark mode's light-green pitch at ~1.45:1. That is what
 * `variantColorResolver` below exists to prevent.
 */

const asTuple = (varName: string): MantineColorsTuple => {
  const value = `var(${varName})`
  return [value, value, value, value, value, value, value, value, value, value]
}

/**
 * Every Mantine colour whose value flips with the colour scheme must resolve
 * to a design token, or Mantine paints surfaces from its own grey ramp that
 * no one designed and `check-contrast.mjs` cannot see (it parses tokens.css).
 * That gap is what put `#868E96` dimmed text on the empty state at 3.14:1,
 * Mantine's `#242424`/`#FFFFFF` body colour in the desktop gutters beside the
 * 660px column, and a `#2E2E2E`/`#424242` filled box around the composer's
 * textarea.
 *
 * The tokens already switch themselves (tokens.css redefines them under both
 * `prefers-color-scheme` and `[data-theme="dark"]`), so the same mapping
 * serves both schemes. It still has to be written into `light` AND `dark`
 * rather than the shared `variables` block: Mantine's own defaults land in
 * `:root[data-mantine-color-scheme="..."]` (0,2,0), which outranks the shared
 * `:root` (0,1,0) block whatever we put there.
 *
 * `--mantine-color-body` is what `@mantine/core`'s baseline.css paints `body`
 * with, so mapping it here is also what makes the page ground match the
 * column — no separate `body { background }` rule needed.
 */
const surfaces = {
  '--mantine-color-body': 'var(--paper)',
  '--mantine-color-text': 'var(--ink)',
  '--mantine-color-dimmed': 'var(--muted)',
  '--mantine-color-placeholder': 'var(--muted)',
  '--mantine-color-default-border': 'var(--rule-section)',
}

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: surfaces,
  dark: surfaces,
})

/**
 * Filled controls get their label colour from `--pitch-contrast` instead of
 * Mantine's hardcoded `theme.white`. Mantine writes the resolver's `color`
 * into an inline `--ai-color`/`--button-color` custom property, so doing it
 * here removes the need for an `!important` override in component CSS and
 * covers every filled control Phase 5 adds, not just the send button.
 *
 * Scoped to the pitch colour deliberately: `--pitch-contrast` is the contrast
 * partner for `--pitch` specifically, and it is the only palette colour that
 * has one. A future `variant="filled" color="redcard"` would need its own
 * token; leaving it on Mantine's default here fails loudly-by-eye rather than
 * silently painting it the wrong contrast colour.
 *
 * Disabled state is untouched: ActionIcon's own
 * `:where(:disabled)` rule sets `color` directly and is declared after the
 * `var(--ai-color)` rule at equal specificity, so it still wins — which is
 * what the removed `.sendButton:not(:disabled)` scoping was protecting.
 */
export const variantColorResolver: VariantColorsResolver = (input) => {
  const base = defaultVariantColorsResolver(input)
  const colorName = input.color ?? input.theme.primaryColor
  if (input.variant === 'filled' && colorName === 'pitch') {
    return { ...base, color: 'var(--pitch-contrast)' }
  }
  return base
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
  variantColorResolver,
})
