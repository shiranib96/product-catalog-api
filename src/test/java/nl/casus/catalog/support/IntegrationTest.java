package nl.casus.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = {
        "catalog.source.scheduled-sync=false",
        "catalog.source.page-size=2",
        "catalog.source.webhook.secret=" + IntegrationTest.WEBHOOK_SECRET})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String WEBHOOK_SECRET = "test-secret";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.properties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("catalog.source.base-url", SourceStub::baseUrl);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE product").update();
        SourceStub.reset();
    }
}
