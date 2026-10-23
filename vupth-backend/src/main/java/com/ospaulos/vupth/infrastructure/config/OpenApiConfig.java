package com.ospaulos.vupth.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Esquema Bearer JWT do Swagger (swagger-authorize): o botao Authorize aparece no dev/staging e as rotas protegidas (/admin...)
 * deixam de dar 401 por la. So descreve a API; na producao o springdoc segue desligado (API_DOCS_ENABLED).
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = OpenApiConfig.BEARER))
@SecurityScheme(name = OpenApiConfig.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";
}
