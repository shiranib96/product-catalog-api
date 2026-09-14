package nl.casus.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import nl.casus.catalog.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void reportsHealth(String endpoint) {
        assertThat(mvc.get().uri(endpoint))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void publishesOpenApiDocument() {
        var api = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();
        api.extractingPath("$.paths['/api/v1/products/search']").isNotNull();
        api.extractingPath("$.paths['/api/v1/product-updates']").isNotNull();
    }
}
