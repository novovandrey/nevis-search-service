package com.nevis.search.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI nevisOpenApi() {
        return new OpenAPI().info(new Info()
                .title("API")
                .version("1.0.0")
                .description("Client creation, text-document storage, client discovery, and PostgreSQL FTS."));
    }
}
