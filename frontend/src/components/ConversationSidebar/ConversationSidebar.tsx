import { Conversation } from '../../types'
import classes from './ConversationSidebar.module.css'

interface Props {
  conversations: Conversation[]
  currentId: string | null
  onSelect: (id: string) => void
  onNew: () => void
  /** Below the 640px breakpoint the sidebar is an overlay; above it this does nothing. */
  open: boolean
}

/**
 * The conversations, most recently used first — the server's order, not this component's.
 *
 * A conversation with no title is one whose first message never landed; it is still listed,
 * because a row the user cannot see is a conversation they cannot get back to.
 */
export default function ConversationSidebar({
  conversations, currentId, onSelect, onNew, open,
}: Props) {
  return (
    <nav className={`${classes.sidebar} ${open ? classes.open : ''}`} aria-label="Conversations">
      <button type="button" className={classes.newChat} onClick={onNew}>
        + New conversation
      </button>
      <ul className={classes.list}>
        {conversations.map(conversation => (
          <li key={conversation.id}>
            <button
              type="button"
              className={`${classes.item} ${conversation.id === currentId ? classes.current : ''}`}
              aria-current={conversation.id === currentId ? 'true' : undefined}
              onClick={() => onSelect(conversation.id)}
            >
              {conversation.title ?? 'Untitled'}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  )
}
