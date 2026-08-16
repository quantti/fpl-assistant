import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
}

// Stats tools prefix their reply with `SeasonContext.provenance()` — a
// banner sentence naming the season and, when it's not the one being
// played, saying so (see backend SeasonContext.java). The model is
// instructed to open its answer with that attribution, so it typically
// arrives as the first line of the assistant's markdown content. Matched
// narrowly (the backend's literal "Data source:" prefix, or a season-code
// lead-in like "2025/26,") so ordinary prose never gets misclassified.
const PROVENANCE_RE = /^(data source:|(?:19|20)\d{2}\/\d{2},)/i

function splitProvenance(content: string): [string | null, string] {
  const newlineIndex = content.indexOf('\n')
  const firstLine = newlineIndex === -1 ? content : content.slice(0, newlineIndex)
  const trimmed = firstLine.trim()
  if (!PROVENANCE_RE.test(trimmed)) return [null, content]
  const rest = newlineIndex === -1 ? '' : content.slice(newlineIndex).replace(/^\n+/, '')
  return [trimmed, rest]
}

export default function AssistantMessage({ content, isStreaming }: Props) {
  const [provenance, body] = splitProvenance(content)

  return (
    <div className={classes.turn}>
      <div className={classes.label}>Assistant</div>
      {provenance && <p className={classes.provenance}>{provenance}</p>}
      <div className={`${classes.prose} ${isStreaming ? classes.cursor : ''}`}>
        <Markdown remarkPlugins={[remarkGfm]}>{body}</Markdown>
      </div>
    </div>
  )
}
