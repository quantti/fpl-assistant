package fi.karivanttinen.fplassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split("\\s*,\\s*"))
                // PUT and DELETE are the profile endpoints. A method missing here fails at
                // preflight, which MockMvc never exercises — so the suite stays green
                // while the browser gets 403 "Invalid CORS request".
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Response headers are not readable cross-origin unless named here, and
                // Retry-After is not on the CORS safelist. Without it a user who has spent their
                // week is told that they are out but never when it frees — invisible locally,
                // because the Vite proxy makes the dev server same-origin.
                .exposedHeaders("Retry-After");
    }
}
