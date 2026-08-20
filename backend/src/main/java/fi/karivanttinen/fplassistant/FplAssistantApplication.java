package fi.karivanttinen.fplassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import fi.karivanttinen.fplassistant.user.Pricing;

@SpringBootApplication
@EnableConfigurationProperties(Pricing.Configured.class)
public class FplAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(FplAssistantApplication.class, args);
    }
}
