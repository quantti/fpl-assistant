package fi.karivanttinen.fplassistant.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SyncOperationsControllerTest {

    @Test
    void statusRequiresDedicatedOperatorToken() throws Exception {
        SyncOperations operations = mock(SyncOperations.class);
        when(operations.status()).thenReturn(new SyncOperations.Status(Instant.EPOCH,
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of()));
        MockMvc mvc = mvc(new SyncOperationsController(operations, true, "operator-secret",
                true, "monitor-secret"));

        mvc.perform(get("/ops/sync/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/sync/status").header(
                        SyncOperationsController.OPERATOR_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/sync/status").header(
                        SyncOperationsController.MONITOR_HEADER, "monitor-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/sync/status").header(
                        SyncOperationsController.OPERATOR_HEADER, "operator-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void externalHealthIsMinimalDedicatedAndDefaultClosed() throws Exception {
        SyncOperations operations = mock(SyncOperations.class);
        when(operations.healthy()).thenReturn(true);
        MockMvc enabled = mvc(new SyncOperationsController(operations, false, "",
                true, "monitor-secret"));
        enabled.perform(get("/ops/sync/health").header(
                        SyncOperationsController.MONITOR_HEADER, "monitor-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.runtimes").doesNotExist());

        MockMvc disabled = mvc(new SyncOperationsController(operations, false, "",
                false, ""));
        disabled.perform(get("/ops/sync/health")).andExpect(status().isNotFound());
        disabled.perform(get("/ops/sync/status")).andExpect(status().isNotFound());
    }

    private static MockMvc mvc(SyncOperationsController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
