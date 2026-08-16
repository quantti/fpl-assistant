import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
}

// Stats tools prefix their reply with `SeasonContext.provenance()`, whose
// text always starts with the literal "Data source: " and always ends with
// one of exactly two fixed sentences (see backend SeasonContext.java):
// "...(the season currently being played)." or "...current form, current
// price, or current ownership." Matching through one of those known
// endings — rather than splitting on the first newline — means a reply
// that runs the provenance sentence directly into the answer with no line
// break (a single unbroken paragraph) still splits correctly instead of
// the whole message getting swallowed into the muted aside with nothing
// left to render as body.
const PROVENANCE_RE =
  /^data source:.*?(?:\(the season currently being played\)|current form, current price, or current ownership)\.\s*/is

function splitProvenance(content: string): [string | null, string] {
  const match = PROVENANCE_RE.exec(content)
  if (!match) return [null, content]
  return [match[0].trim(), content.slice(match[0].length)]
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
