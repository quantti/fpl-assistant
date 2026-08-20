import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import typography from '../../styles/typography.module.css'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
  isError?: boolean
  isNotice?: boolean
}

export default function AssistantMessage({ content, isStreaming, isError, isNotice }: Props) {
  const label = isError ? 'Error' : isNotice ? 'Note' : 'Assistant'
  const labelClass = isError
    ? classes.errorLabel
    : isNotice
      ? classes.noticeLabel
      : classes.assistantLabel

  return (
    <div className={typography.turn}>
      <div className={`${typography.label} ${labelClass}`}>{label}</div>
      <div className={`${classes.prose} ${isStreaming ? classes.cursor : ''}`}>
        <Markdown remarkPlugins={[remarkGfm]}>{content}</Markdown>
      </div>
    </div>
  )
}
