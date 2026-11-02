package dev.quantti.fplassistant.rag;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A store failure becomes a plain-text tool reply without needing an application context. */
class StrategyToolsErrorTest {

    @Test
    void storeFailureReturnsErrorStringInsteadOfThrowing() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new DataAccessResourceFailureException("simulated store failure"));
        StrategyTools strategyTools = new StrategyTools(store,
                new StaticListableBeanFactory().getBeanProvider(Clock.class));

        String result = strategyTools.searchStrategyDocs("captain advice");

        assertThat(result).contains("temporarily unavailable");
    }
}
