package dev.quantti.fplassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import dev.quantti.fplassistant.user.Pricing;

@SpringBootApplication
@EnableConfigurationProperties(Pricing.Configured.class)
public class FplAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(FplAssistantApplication.class, args);
    }
}
