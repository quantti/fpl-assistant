import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import typography from '../../styles/typography.module.css'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
  isError?: boolean
}

export default function AssistantMessage({ content, isStreaming, isError }: Props) {
  return (
    <div className={typography.turn}>
      <div
        className={`${typography.label} ${isError ? classes.errorLabel : classes.assistantLabel}`}
      >
        {isError ? 'Error' : 'Assistant'}
      </div>
      <div className={`${classes.prose} ${isStreaming ? classes.cursor : ''}`}>
        <Markdown remarkPlugins={[remarkGfm]}>{content}</Markdown>
      </div>
    </div>
  )
}
