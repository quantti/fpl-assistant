import { describe, expect, it } from 'vitest'
import type { CataloguePlayer, PlayerCatalogue } from '../../types'
import { mentionNodes, playerMentions, recognizedAliases } from './playerMentions'

const players: CataloguePlayer[] = [
  player(17, 'Saka', 'Bukayo Saka', ['Bukayo Saka', 'Saka']),
  player(18, 'Smith', 'Adam Smith', ['Adam Smith', 'Smith']),
  player(19, 'Smith', 'Samuel Smith', ['Samuel Smith', 'Smith']),
  player(20, 'Son', 'Son Heung-min', ['Son Heung-min', 'Son']),
]
const catalogue: PlayerCatalogue = { catalogueId: 'catalogue-1', season: '2026-27', players }

function player(elementId: number, webName: string, fullName: string, aliases: string[]): CataloguePlayer {
  return { elementId, webName, fullName, aliases, teamShort: 'TST', teamName: 'Test', elementType: 3 }
}

function links(value: string) {
  return mentionNodes(value, recognizedAliases(catalogue)).filter(node => node.type === 'link')
}

describe('player mention recognition', () => {
  it('uses longest exact aliases, repeatedly and case-insensitively', () => {
    const found = links('Bukayo Saka prefers SAKA; Saka stays.')
    expect(found).toHaveLength(3)
    expect(found.map(node => node.children?.[0].value)).toEqual(['Bukayo Saka', 'SAKA', 'Saka'])
  })

  it('leaves ambiguous aliases plain while retaining unambiguous full names', () => {
    expect(links('Smith, Adam Smith and Samuel Smith.').map(node => node.children?.[0].value))
      .toEqual(['Adam Smith', 'Samuel Smith'])
  })

  it('enforces letter and number boundaries but accepts punctuation and possessives', () => {
    expect(links("Sakaria and xSaka are plain; Saka's pick and (Son) count.")
      .map(node => node.children?.[0].value)).toEqual(['Saka', 'Son'])
  })

  it('collapses alias whitespace defensively and matches rendered whitespace', () => {
    const spaced = { ...catalogue, players: [player(21, 'Van Dijk', 'Virgil van Dijk', ['Virgil   van Dijk'])] }
    expect(mentionNodes('Virgil van   Dijk', recognizedAliases(spaced)).filter(n => n.type === 'link'))
      .toHaveLength(1)
  })

  it('transforms table and inline-format text leaves but never descends into existing links', () => {
    const tree: any = {
      type: 'root',
      children: [
        { type: 'table', children: [{ type: 'tableCell', children: [{ type: 'text', value: 'Saka' }] }] },
        { type: 'paragraph', children: [{ type: 'strong', children: [{ type: 'text', value: 'Son' }] }] },
        { type: 'paragraph', children: [
          { type: 'link', url: '/players/saka', children: [{ type: 'text', value: 'Saka' }] },
        ] },
      ],
    }
    const transform = playerMentions(catalogue)()
    transform(tree)

    expect(tree.children[0].children[0].children[0].type).toBe('link')
    expect(tree.children[1].children[0].type).toBe('link')
    expect(tree.children[1].children[0].children[0].type).toBe('strong')
    expect(tree.children[2].children[0].type).toBe('link')
    expect(tree.children[2].children[0].data).toBeUndefined()
  })

  it('matches full names across formatting and checks boundaries across text leaves', () => {
    const tree: any = {
      type: 'root',
      children: [{
        type: 'paragraph',
        children: [
          { type: 'text', value: 'Adam ' },
          { type: 'strong', children: [{ type: 'text', value: 'Smith' }] },
          { type: 'text', value: ' and ' },
          { type: 'strong', children: [{ type: 'text', value: 'Saka' }] },
          { type: 'text', value: 'ria' },
        ],
      }],
    }
    playerMentions(catalogue)()(tree)

    const transformed = tree.children[0].children
    expect(transformed.filter((node: any) => node.data)).toHaveLength(1)
    expect(transformed.find((node: any) => node.data).children[1].type).toBe('strong')
    const rendered = (node: any): string => node.value ?? node.children?.map(rendered).join('') ?? ''
    expect(transformed.map(rendered).join('')).toContain('Sakaria')
  })
})
