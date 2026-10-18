package fi.karivanttinen.fplassistant.price;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(PricePredictionEnabled.Gate.class)
public @interface PricePredictionEnabled {
    final class Gate implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean allowed = PricePredictionPermission.configured(context.getEnvironment());
            if (!allowed && "true".equalsIgnoreCase(context.getEnvironment()
                    .getProperty("fpl.price-prediction.enabled", "false"))) {
                org.slf4j.LoggerFactory.getLogger(PricePredictionEnabled.class)
                        .warn("Price prediction is disabled: owned-sync mode must be OFF");
            }
            return allowed;
        }
    }
}
