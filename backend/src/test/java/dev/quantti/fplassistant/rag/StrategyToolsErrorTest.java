package dev.quantti.fplassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Error path: a store failure must surface as a plain-text message, not an exception that
 * kills the SSE stream. Spring Boot 4.1 has no @MockitoBean/@MockBean (mock support was
 * moved out of spring-boot-test), so the store is stubbed with a @Primary Mockito mock in
 * a nested test configuration — the pgvector autoconfiguration backs off on the existing
 * VectorStore bean.
 */
@SpringBootTest
@ActiveProfiles("test")
class StrategyToolsErrorTest {

    @TestConfiguration
    static class ThrowingVectorStore {
        @Bean
        @Primary
        VectorStore throwingVectorStore() {
            VectorStore mock = mock(VectorStore.class);
            when(mock.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new DataAccessResourceFailureException("simulated store failure"));
            return mock;
        }
    }

    @Autowired
    StrategyTools strategyTools;

    @Test
    void storeFailureReturnsErrorStringInsteadOfThrowing() {
        String result = strategyTools.searchStrategyDocs("captain advice");

        assertThat(result).contains("temporarily unavailable");
    }
}
