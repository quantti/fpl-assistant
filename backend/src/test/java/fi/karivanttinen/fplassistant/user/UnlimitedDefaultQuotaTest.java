package fi.karivanttinen.fplassistant.user;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code -1} is documented beside the property as "unlimited", so someone will eventually set it
 * there rather than per user. Read literally it refuses every account that has no override, with
 * "you have used all -1 messages" — the configured default has to pass through the same sentinel
 * the column does.
 */
@SpringBootTest(properties = "fpl.quota.free-messages-per-week=-1")
@ActiveProfiles("test")
class UnlimitedDefaultQuotaTest {

    @Autowired
    UsageService usage;

    @Test
    void aNegativeDefaultMeansUnlimitedForEveryoneWithoutAnOverride() {
        UsageService.Quota quota = usage.quotaOf(UUID.randomUUID());

        assertThat(quota.limit()).isNull();
        assertThat(quota.exceeded()).isFalse();
    }
}
