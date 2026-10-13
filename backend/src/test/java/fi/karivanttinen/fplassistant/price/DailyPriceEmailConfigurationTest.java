package fi.karivanttinen.fplassistant.price;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DailyPriceEmailConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(application -> {
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(new ClassPathResource("application.yml"));
                application.getEnvironment().getPropertySources().addLast(
                        new PropertiesPropertySource("application-yaml", yaml.getObject()));
            })
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(DailyPriceEmailAlert.class, DailyPriceFreshnessCheck.class);

    @Test
    void checkedInDefaultsWireTheCheckButDisableMail() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(DailyPriceFreshnessCheck.class);
            DailyPriceEmailAlert sender = application.getBean(DailyPriceEmailAlert.class);
            assertThat(ReflectionTestUtils.getField(sender, "enabled")).isEqualTo(false);
            assertThat(ReflectionTestUtils.getField(sender, "apiKey")).isEqualTo("");
            assertThat(ReflectionTestUtils.getField(sender, "to")).isEqualTo("kvanttinen@gmail.com");
            assertThat(ReflectionTestUtils.getField(sender, "from"))
                    .isEqualTo("FPL Sidekick <noreply@fpl-sidekick.com>");
        });
    }

    @Test
    void disablingCaptureAlsoRemovesTheCheckEvenWhenMailIsEnabled() {
        context.withPropertyValues("fpl.daily-price.scheduled=false",
                "DAILY_PRICE_ALERT_EMAIL_ENABLED=true", "DAILY_PRICE_ALERT_RESEND_API_KEY=test-key")
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(DailyPriceFreshnessCheck.class);
                    DailyPriceEmailAlert sender = application.getBean(DailyPriceEmailAlert.class);
                    assertThat(ReflectionTestUtils.getField(sender, "enabled")).isEqualTo(true);
                    assertThat(ReflectionTestUtils.getField(sender, "apiKey")).isEqualTo("test-key");
                });
    }
}
