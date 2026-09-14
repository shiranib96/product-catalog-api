package nl.casus.catalog.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Product Catalog API",
        version = "v1",
        description = "Search the catalog. Products and prices come from the source: pulled periodically and pushed "
                + "through a webhook."))
class OpenApiConfiguration {
}
