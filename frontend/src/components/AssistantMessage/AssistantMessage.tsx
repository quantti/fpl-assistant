import { useMemo } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { PlayerCatalogue } from '../../types'
import typography from '../../styles/typography.module.css'
import PlayerMention from './PlayerMention'
import { MENTION_DATA, playerMentions } from './playerMentions'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
  isError?: boolean
  isNotice?: boolean
  catalogue?: PlayerCatalogue
}

export default function AssistantMessage({ content, isStreaming, isError, isNotice, catalogue }: Props) {
  const label = isError ? 'Error' : isNotice ? 'Note' : 'Assistant'
  const labelClass = isError
    ? classes.errorLabel
    : isNotice
      ? classes.noticeLabel
      : classes.assistantLabel
  const mentionPlugin = useMemo(() => catalogue ? playerMentions(catalogue) : null, [catalogue])
  const players = useMemo(() => new Map(catalogue?.players.map(player => [player.elementId, player])), [catalogue])

  return (
    <div className={typography.turn}>
      <div className={`${typography.label} ${labelClass}`}>{label}</div>
      <div className={`${classes.prose} ${isStreaming ? classes.cursor : ''}`}>
        <Markdown
          remarkPlugins={mentionPlugin ? [remarkGfm, mentionPlugin] : [remarkGfm]}
          components={{
            a: ({ href, title, children, node, ...props }) => {
              const properties = node?.properties as Record<string, unknown> | undefined
              const elementId = Number(properties?.[MENTION_DATA] ?? properties?.dataFplPlayer)
              const player = Number.isInteger(elementId) ? players.get(elementId) : undefined
              if (catalogue && player) {
                return <PlayerMention catalogue={catalogue} player={player}>{children}</PlayerMention>
              }
              return <a href={href} title={title} {...props}>{children}</a>
            },
          }}
        >{content}</Markdown>
      </div>
    </div>
  )
}
