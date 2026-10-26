package com.tm.storedprocedure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ValidatorPropertiesBindingTest {
    @Test
    void bindsDatabaseAndSchemaFromApplicationYamlToValidatorProperties() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addFirst(source);
        }

        ValidatorProperties properties = Binder.get(environment)
                .bind("validator", Bindable.of(ValidatorProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.getDatabase()).isEqualTo("tm");
        assertThat(properties.getSchema()).isEqualTo("public1");
    }
}
