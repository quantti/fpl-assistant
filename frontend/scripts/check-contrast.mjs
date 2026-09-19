#!/usr/bin/env node
/**
 * Parses frontend/src/styles/tokens.css and computes WCAG relative-luminance
 * contrast ratios for the pairings tabulated in
 * docs/superpowers/specs/2026-08-15-frontend-design-system.md, failing the
 * build if any pairing drops below the WCAG level the spec assigns it.
 *
 * Colour values are read from tokens.css itself, never duplicated here, so
 * the guard actually exercises whatever is currently committed.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const tokensPath = path.join(__dirname, '..', 'src', 'styles', 'tokens.css');

// ---------------------------------------------------------------------------
// WCAG relative-luminance contrast math
// (https://www.w3.org/TR/WCAG21/#dfn-relative-luminance)
// ---------------------------------------------------------------------------

function hexToRgb(hex) {
  const n = hex.replace('#', '');
  return {
    r: parseInt(n.slice(0, 2), 16),
    g: parseInt(n.slice(2, 4), 16),
    b: parseInt(n.slice(4, 6), 16),
  };
}

function linearize(channel8bit) {
  const c = channel8bit / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function relativeLuminance(hex) {
  const { r, g, b } = hexToRgb(hex);
  return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
}

function contrastRatio(hexA, hexB) {
  const la = relativeLuminance(hexA);
  const lb = relativeLuminance(hexB);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

// Sanity check the math itself before trusting it as a gate: pure black on
// pure white must compute to exactly 21.00.
const sanity = contrastRatio('#000000', '#FFFFFF');
if (Math.abs(sanity - 21) > 0.005) {
  console.error(
    `check-contrast: sanity check failed — black on white computed as ${sanity.toFixed(2)}, expected 21.00. Contrast math is broken; refusing to trust it as a gate.`,
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Parse tokens.css
// ---------------------------------------------------------------------------

// Comments are stripped before anything is matched. Without this the parser
// happily reads a commented-out declaration — `/* try --paper: #FFFFFF; */`
// placed after the real one wins, because the regexes below scan in source
// order and the last match for a token is the one kept. This is the build's
// accessibility gate; it must read what the browser reads.
const css = readFileSync(tokensPath, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Extracts the brace-balanced body of the first block whose opening matches `startRegex`. */
function extractBlockBody(source, startRegex) {
  const startMatch = startRegex.exec(source);
  if (!startMatch) return null;
  const openBrace = source.indexOf('{', startMatch.index);
  let depth = 0;
  let i = openBrace;
  for (; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}') {
      depth--;
      if (depth === 0) break;
    }
  }
  return source.slice(openBrace + 1, i);
}

function parseHexColors(blockBody) {
  const colors = {};
  const re = /--([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g;
  let m;
  while ((m = re.exec(blockBody))) {
    colors[m[1]] = m[2].toUpperCase();
  }
  return colors;
}

/**
 * Resolves single-level `--name: var(--other);` indirection (e.g. --ink:
 * var(--ink-900);) against `ownColors` first, falling back to
 * `fallbackColors` for tokens the block doesn't itself redeclare — mirroring
 * the CSS cascade, where a dark-mode block that doesn't redeclare a token
 * still reads it from the bare `:root` block. Returns a new map combining
 * `ownColors` with whatever var() references resolved to a hex.
 */
function resolveVarRefs(blockBody, ownColors, fallbackColors) {
  const resolved = { ...ownColors };
  const re = /--([\w-]+)\s*:\s*var\(--([\w-]+)\)\s*;/g;
  let m;
  while ((m = re.exec(blockBody))) {
    const [, name, ref] = m;
    const value = ownColors[ref] ?? fallbackColors[ref];
    if (value) resolved[name] = value;
  }
  return resolved;
}

// Bare `:root {` — not `:root:not(...)` or `:root[...]`, both of which have
// a non-brace, non-whitespace character immediately after `:root`.
const lightBody = extractBlockBody(css, /^:root\s*\{/m);
if (!lightBody) {
  console.error('check-contrast: could not find a bare `:root { ... }` block in tokens.css');
  process.exit(1);
}
const lightHexColors = parseHexColors(lightBody);
const lightColors = resolveVarRefs(lightBody, lightHexColors, lightHexColors);

const darkMediaBody = extractBlockBody(css, /:root:not\(\[data-theme="light"\]\)\s*\{/);
const darkAttrBody = extractBlockBody(css, /:root\[data-theme="dark"\]\s*\{/);
if (!darkMediaBody || !darkAttrBody) {
  console.error(
    'check-contrast: could not find both dark-mode override blocks (`prefers-color-scheme` and `[data-theme="dark"]`) in tokens.css',
  );
  process.exit(1);
}
const darkMediaColors = resolveVarRefs(darkMediaBody, parseHexColors(darkMediaBody), lightColors);
const darkAttrColors = resolveVarRefs(darkAttrBody, parseHexColors(darkAttrBody), lightColors);

// The whole point of overriding via both `prefers-color-scheme` and
// `data-theme="dark"` is that a future toggle can force dark regardless of
// OS setting. If the two blocks drift apart, that guarantee silently
// breaks — so we gate on them being identical.
const mediaKeys = Object.keys(darkMediaColors).sort();
const attrKeys = Object.keys(darkAttrColors).sort();
let darkBlocksDiverge =
  mediaKeys.length !== attrKeys.length ||
  mediaKeys.some((k) => darkMediaColors[k] !== darkAttrColors[k]) ||
  attrKeys.some((k) => !(k in darkMediaColors));
if (darkBlocksDiverge) {
  console.error(
    'check-contrast: the `@media (prefers-color-scheme: dark)` block and the `[data-theme="dark"]` block define different colours. They must stay identical so the theme toggle can override the OS preference in both directions.',
  );
  console.error('  prefers-color-scheme block:', darkMediaColors);
  console.error('  data-theme block:          ', darkAttrColors);
  process.exit(1);
}

const darkColors = { ...lightColors, ...darkAttrColors };

// ---------------------------------------------------------------------------
// Pairings the spec tabulates
// ---------------------------------------------------------------------------

const AAA = 7;
const AA = 4.5;
const LARGE_OR_UI = 3;

function resolve(colors, token) {
  const value = colors[token];
  if (!value) throw new Error(`check-contrast: token --${token} not found`);
  return value;
}

const pairs = [
  // Light theme — background is --paper unless noted.
  {
    theme: 'light',
    fg: 'ink',
    bg: 'paper',
    label: 'ink on paper (body text)',
    level: 'AAA',
    min: AAA,
  },
  { theme: 'light', fg: 'muted', bg: 'paper', label: 'muted on paper', level: 'AAA', min: AAA },
  {
    theme: 'light',
    fg: 'pitch',
    bg: 'paper',
    label: 'pitch on paper (links, primary action)',
    level: 'AA',
    min: AA,
  },
  { theme: 'light', fg: 'white', bg: 'pitch', label: 'white label on pitch', level: 'AA', min: AA },
  // The pairing components actually render (the send button). Distinct from
  // the row above even in light, where --pitch-contrast merely happens to
  // equal --white today — that equality is a fact about the current palette,
  // not a guarantee, and the row above would keep passing if it broke.
  {
    theme: 'light',
    fg: 'pitch-contrast',
    bg: 'pitch',
    label: 'pitch-contrast label on pitch (send button)',
    level: 'AA',
    min: AA,
  },
  { theme: 'light', fg: 'ink', bg: 'card', label: 'ink label on card', level: 'AAA', min: AAA },
  { theme: 'light', fg: 'redcard', bg: 'paper', label: 'redcard on paper', level: 'AAA', min: AAA },
  {
    theme: 'light',
    fg: 'white',
    bg: 'redcard',
    label: 'white label on redcard',
    level: 'AAA',
    min: AAA,
  },
  {
    theme: 'light',
    fg: 'flood',
    bg: 'paper',
    label: 'flood on paper (large text / UI only)',
    level: 'large/UI',
    min: LARGE_OR_UI,
  },
  { theme: 'light', fg: 'kit', bg: 'paper', label: 'kit on paper', level: 'AA', min: AA },
  // Documented as honest limits, not gated — decorative separators are exempt from WCAG text-contrast levels.
  {
    theme: 'light',
    fg: 'rule-row',
    bg: 'paper',
    label: 'rule-row on paper (decorative)',
    level: null,
    min: null,
  },
  {
    theme: 'light',
    fg: 'rule-section',
    bg: 'paper',
    label: 'rule-section on paper (section boundary)',
    level: null,
    min: null,
  },
  // Recorded, not gated: WCAG 1.4.3 exempts inactive components, and these
  // are meant to sit low — a disabled control that measures like body text
  // does not read as disabled. Listed so the values are drift-protected and
  // a future change to either token shows up in this output.
  {
    theme: 'light',
    fg: 'disabled-ink',
    bg: 'disabled-surface',
    label: 'disabled label on disabled surface (inactive by design)',
    level: null,
    min: null,
  },

  // Dark theme — background is --paper (the "ground" value in dark mode).
  { theme: 'dark', fg: 'ink', bg: 'paper', label: 'body text on ground', level: 'AAA', min: AAA },
  // Dark --muted was added 2026-08-17; before that --muted kept its light
  // navy in dark mode at 2.16:1 and nothing here noticed, because this
  // section had no muted row. It does now.
  { theme: 'dark', fg: 'muted', bg: 'paper', label: 'muted on ground', level: 'AAA', min: AAA },
  {
    theme: 'dark',
    fg: 'pitch',
    bg: 'paper',
    label: 'pitch on ground (links)',
    level: 'AAA',
    min: AAA,
  },
  {
    theme: 'dark',
    fg: 'pitch-contrast',
    bg: 'pitch',
    label: 'pitch-contrast label on pitch (send button)',
    level: 'AAA',
    min: AAA,
  },
  { theme: 'dark', fg: 'card', bg: 'paper', label: 'card on ground', level: 'AAA', min: AAA },
  { theme: 'dark', fg: 'redcard', bg: 'paper', label: 'redcard on ground', level: 'AAA', min: AAA },
  {
    theme: 'dark',
    fg: 'disabled-ink',
    bg: 'disabled-surface',
    label: 'disabled label on disabled surface (inactive by design)',
    level: null,
    min: null,
  },
];

let failed = false;
console.log('check-contrast: sanity check (black on white) = 21.00 OK\n');

for (const pair of pairs) {
  const colors = pair.theme === 'dark' ? darkColors : lightColors;
  const fgHex = resolve(colors, pair.fg);
  const bgHex = resolve(colors, pair.bg);
  const ratio = contrastRatio(fgHex, bgHex);
  const ratioStr = ratio.toFixed(2);

  if (pair.min === null) {
    console.log(
      `  [info] ${pair.theme.padEnd(5)} ${pair.label}: ${ratioStr} (${fgHex} on ${bgHex}, no WCAG minimum — ${pair.label.includes('decorative') ? 'decorative' : 'documented limit'})`,
    );
    continue;
  }

  const pass = ratio >= pair.min;
  if (!pass) failed = true;
  const status = pass ? 'PASS' : 'FAIL';
  console.log(
    `  [${status}] ${pair.theme.padEnd(5)} ${pair.label}: ${ratioStr} (${fgHex} on ${bgHex}) — needs >= ${pair.min} for ${pair.level}`,
  );
}

console.log();
if (failed) {
  console.error('check-contrast: one or more pairings fall below their stated WCAG level.');
  process.exit(1);
}
console.log('check-contrast: all gated pairings meet their stated WCAG level.');
