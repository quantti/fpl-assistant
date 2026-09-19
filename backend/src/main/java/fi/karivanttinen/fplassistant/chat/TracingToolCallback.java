package fi.karivanttinen.fplassistant.chat;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Records a tool invocation while passing the framework's private ToolContext through unchanged. */
final class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ChatTraceCollector trace;

    TracingToolCallback(ToolCallback delegate, ChatTraceCollector trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return invoke(input, () -> delegate.call(input));
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        return invoke(input, () -> delegate.call(input, toolContext));
    }

    private String invoke(String input, Invocation invocation) {
        ChatTraceCollector.ToolHandle handle = trace.startTool(
                delegate.getToolDefinition().name(), input);
        try {
            String result = invocation.call();
            trace.finishTool(handle, result, null);
            return result;
        } catch (RuntimeException | Error failure) {
            trace.finishTool(handle, null, failure);
            throw failure;
        }
    }

    @FunctionalInterface
    private interface Invocation {
        String call();
    }
}
