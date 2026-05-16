package com.aisandbox.server.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level springdoc {@link OpenAPI} bean — title, description, license.
 * The path mappings ({@code /v1/openapi.yaml}, {@code /v1/swagger-ui})
 * are set in {@code application.yaml}.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI aiSandboxServerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ai-sandbox management server")
                        .version("0.0.1")
                        .description("mTLS-gated REST + WebSocket API for the ai-sandbox UC02 multi-session kit.")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
