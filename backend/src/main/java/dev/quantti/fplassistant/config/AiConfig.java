package dev.quantti.fplassistant.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(
            @Value("${fpl.ai.provider:anthropic}") String provider,
            ObjectProvider<AnthropicChatModel> anthropicModel,
            ObjectProvider<OpenAiChatModel> openAiModel,
            @Value("classpath:prompts/system-prompt.st") Resource systemPrompt
    ) throws IOException {
        String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        ChatModel model = "openai".equals(provider)
                ? openAiModel.getIfAvailable()
                : anthropicModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException(
                "No ChatModel for provider '" + provider + "'. "
                + "Set SPRING_AI_PROVIDER to 'anthropic' or 'openai' and provide the matching API key.");
        }
        // The response-token cap is set per provider in application.yml: the portable
        // ChatOptions.maxTokens maps to OpenAI's legacy max_tokens, which gpt-5.x rejects.
        return ChatClient.builder(model)
                .defaultSystem(prompt)
                .build();
    }
}
