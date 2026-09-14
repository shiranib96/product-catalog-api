package nl.casus.catalog.source;

import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class SourceClient {

    private final RestClient restClient;

    SourceClient(RestClient.Builder builder, SourceProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    SourceProductPage fetchProducts(int page, int size) {
        return restClient.get()
                .uri("/api/products?page={page}&size={size}", page, size)
                .retrieve()
                .body(SourceProductPage.class);
    }

    void registerWebhook(String callbackUrl, String secret) {
        restClient.post()
                .uri("/api/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WebhookRegistration(callbackUrl, secret))
                .retrieve()
                .toBodilessEntity();
    }

    record WebhookRegistration(String url, String secret) {
    }
}
