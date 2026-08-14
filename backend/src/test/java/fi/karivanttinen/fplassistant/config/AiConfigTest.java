package fi.karivanttinen.fplassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiConfigTest {

    @Autowired
    ChatClient chatClient;

    @Test
    void chatClientBeanCreated() {
        assertThat(chatClient).isNotNull();
    }
}
