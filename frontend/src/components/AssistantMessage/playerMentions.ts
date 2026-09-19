import type { CataloguePlayer, PlayerCatalogue } from '../../types';

type MdNode = {
  type: string;
  value?: string;
  url?: string;
  children?: MdNode[];
  data?: { hProperties?: Record<string, string | number> };
};

type Alias = { key: string; source: string; player: CataloguePlayer };
type Match = { start: number; end: number; text: string; alias: Alias };

export const MENTION_DATA = 'data-fpl-player';

export function normalizeAlias(value: string): string {
  return value.trim().replace(/\s+/gu, ' ').toLocaleLowerCase('en-GB');
}

export function recognizedAliases(catalogue: PlayerCatalogue): Alias[] {
  const owners = new Map<string, Map<number, Alias>>();
  for (const player of catalogue.players) {
    for (const source of player.aliases) {
      const key = normalizeAlias(source);
      if (!key) continue;
      const byPlayer = owners.get(key) ?? new Map<number, Alias>();
      byPlayer.set(player.elementId, { key, source: source.trim(), player });
      owners.set(key, byPlayer);
    }
  }
  return [...owners.values()]
    .filter((byPlayer) => byPlayer.size === 1)
    .map((byPlayer) => [...byPlayer.values()][0])
    .sort(
      (a, b) =>
        b.key.length - a.key.length ||
        a.key.localeCompare(b.key) ||
        a.player.elementId - b.player.elementId,
    );
}

function escapedAlias(alias: string): string {
  return alias
    .split(/\s+/u)
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('\\s+');
}

function matches(value: string, aliases: Alias[]): Match[] {
  if (!value || aliases.length === 0) return [];
  const pattern = new RegExp(
    `(?:${aliases.map((alias) => escapedAlias(alias.source)).join('|')})`,
    'giu',
  );
  const byKey = new Map(aliases.map((alias) => [alias.key, alias]));
  const found: Match[] = [];
  for (const match of value.matchAll(pattern)) {
    const start = match.index;
    const end = start + match[0].length;
    const before = start === 0 ? '' : value[start - 1];
    const after = end === value.length ? '' : value[end];
    if ((before && /[\p{L}\p{N}]/u.test(before)) || (after && /[\p{L}\p{N}]/u.test(after)))
      continue;
    const alias = byKey.get(normalizeAlias(match[0]));
    if (alias) found.push({ start, end, text: match[0], alias });
  }
  return found;
}

function mention(alias: Alias, children: MdNode[]): MdNode {
  return {
    type: 'link',
    url: '#',
    children,
    // Application-only AST metadata. Model-authored links cannot forge an element id.
    data: { hProperties: { [MENTION_DATA]: alias.player.elementId } },
  };
}

/** Splits one plain Markdown text leaf. Exported so boundary/ambiguity logic stays directly tested. */
export function mentionNodes(value: string, aliases: Alias[]): MdNode[] {
  const found = matches(value, aliases);
  if (!found.length) return [{ type: 'text', value }];
  const nodes: MdNode[] = [];
  let cursor = 0;
  for (const match of found) {
    if (match.start > cursor) nodes.push({ type: 'text', value: value.slice(cursor, match.start) });
    nodes.push(mention(match.alias, [{ type: 'text', value: match.text }]));
    cursor = match.end;
  }
  if (cursor < value.length) nodes.push({ type: 'text', value: value.slice(cursor) });
  return nodes;
}

function textOf(node: MdNode): string {
  if (node.type === 'text' || node.type === 'inlineCode') return node.value ?? '';
  return node.children?.map(textOf).join('') ?? '';
}

/** Clones only the rendered-text interval while retaining emphasis/strong formatting. */
function slice(node: MdNode, from: number, to: number): MdNode | null {
  if (to <= from) return null;
  if (node.type === 'text' || node.type === 'inlineCode') {
    return { ...node, value: (node.value ?? '').slice(from, to) };
  }
  if (!node.children) return null;
  const children: MdNode[] = [];
  let offset = 0;
  for (const child of node.children) {
    const length = textOf(child).length;
    const part = slice(child, Math.max(0, from - offset), Math.min(length, to - offset));
    if (part) children.push(part);
    offset += length;
  }
  return children.length ? { ...node, children } : null;
}

function slices(nodes: MdNode[], from: number, to: number): MdNode[] {
  const result: MdNode[] = [];
  let offset = 0;
  for (const node of nodes) {
    const length = textOf(node).length;
    const part = slice(node, Math.max(0, from - offset), Math.min(length, to - offset));
    if (part) result.push(part);
    offset += length;
  }
  return result;
}

function transformRun(nodes: MdNode[], aliases: Alias[]): MdNode[] {
  const value = nodes.map(textOf).join('');
  const found = matches(value, aliases);
  if (!found.length) return nodes;
  const result: MdNode[] = [];
  let cursor = 0;
  for (const match of found) {
    result.push(...slices(nodes, cursor, match.start));
    result.push(mention(match.alias, slices(nodes, match.start, match.end)));
    cursor = match.end;
  }
  result.push(...slices(nodes, cursor, value.length));
  return result;
}

const phrasingContainers = new Set(['paragraph', 'heading', 'tableCell']);
const opaqueTypes = new Set(['link', 'linkReference', 'image', 'break', 'html']);
type PhrasingPart = { node: MdNode; opaque: boolean };

/**
 * Lifts opaque descendants to the container run while cloning their surrounding formatting.
 * This keeps `**[Saka](...)**` a link inside strong text, but prevents the surrounding strong
 * node from hiding that link from the no-nested-controls barrier.
 */
function phrasingParts(node: MdNode): PhrasingPart[] {
  if (opaqueTypes.has(node.type)) return [{ node, opaque: true }];
  if (!node.children || !node.children.some((child) => containsOpaque(child))) {
    return [{ node, opaque: false }];
  }
  return node.children.flatMap((child) =>
    phrasingParts(child).map((part) => ({
      node: { ...node, children: [part.node] },
      opaque: part.opaque,
    })),
  );
}

function containsOpaque(node: MdNode): boolean {
  return opaqueTypes.has(node.type) || Boolean(node.children?.some(containsOpaque));
}

/** Remark plugin: recognizes rendered inline text and code, with authored links as hard barriers. */
export function playerMentions(catalogue: PlayerCatalogue) {
  const aliases = recognizedAliases(catalogue);
  return () => (tree: MdNode) => {
    const visit = (node: MdNode): void => {
      if (!node.children) return;
      if (phrasingContainers.has(node.type)) {
        const output: MdNode[] = [];
        let run: MdNode[] = [];
        const flush = () => {
          output.push(...transformRun(run, aliases));
          run = [];
        };
        for (const child of node.children) {
          for (const part of phrasingParts(child)) {
            if (part.opaque || textOf(part.node) === '') {
              flush();
              output.push(part.node);
            } else {
              run.push(part.node);
            }
          }
        }
        flush();
        node.children = output;
        return;
      }
      for (const child of node.children) visit(child);
    };
    visit(tree);
  };
}
