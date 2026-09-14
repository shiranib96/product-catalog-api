package nl.casus.catalog.source;

import static java.util.Comparator.comparing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import nl.casus.catalog.update.PriceUpdate;
import nl.casus.catalog.update.ProductUpdate;
import nl.casus.catalog.update.UpdateResult;
import nl.casus.catalog.update.UpdateService;
import nl.casus.catalog.update.UpdateService.Channel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/product-updates")
@Tag(name = "Product updates")
class ProductWebhookController {

    static final String SIGNATURE_HEADER = "X-Signature";

    private final WebhookSignature signature;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final UpdateService updates;

    ProductWebhookController(WebhookSignature signature, JsonMapper jsonMapper, Validator validator,
                             UpdateService updates) {
        this.signature = signature;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        this.updates = updates;
    }

    // takes the raw body because the signature has to be checked on the exact bytes that were sent
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Webhook for product and price changes from the source",
            description = "Changed or new products (all product data) and/or price changes. The body must be signed: "
                    + "X-Signature: sha256=<hex HMAC-SHA256 of the body with the shared secret>. Product data and "
                    + "prices are only applied when they are newer than what we have.")
    UpdateResult receive(@RequestHeader(name = SIGNATURE_HEADER, required = false) String signatureHeader,
                         @RequestBody byte[] body) {
        if (!signature.matches(body, signatureHeader)) {
            throw new InvalidSignatureException();
        }
        SourceEvent event = parse(body);
        List<ProductUpdate> products = event.products().stream().map(SourceMapper::toProductUpdate).toList();
        List<PriceUpdate> prices = Stream.concat(
                        event.products().stream().flatMap(product -> SourceMapper.toPriceUpdate(product).stream()),
                        event.prices().stream().map(SourceMapper::toPriceUpdate))
                .toList();
        return updates.applyAll(products, prices, Channel.WEBHOOK);
    }

    private SourceEvent parse(byte[] body) {
        SourceEvent event;
        try {
            event = jsonMapper.readValue(body, SourceEvent.class);
        } catch (JacksonException e) {
            throw new InvalidPayloadException("Body is not valid JSON: " + e.getOriginalMessage(), List.of());
        }
        if (event == null) {
            throw new InvalidPayloadException("Body is empty", List.of());
        }
        var errors = validator.validate(event).stream()
                .map(violation -> Map.of("field", violation.getPropertyPath().toString(), "message", violation.getMessage()))
                .sorted(comparing(error -> error.get("field")))
                .toList();
        if (!errors.isEmpty()) {
            throw new InvalidPayloadException("The update is invalid", errors);
        }
        return event;
    }

    @ExceptionHandler
    ProblemDetail handleInvalidSignature(InvalidSignatureException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Missing or wrong " + SIGNATURE_HEADER + " header");
        problem.setTitle("Invalid signature");
        return problem;
    }

    @ExceptionHandler
    ProblemDetail handleInvalidPayload(InvalidPayloadException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid update");
        if (!e.errors.isEmpty()) {
            problem.setProperty("errors", e.errors);
        }
        return problem;
    }

    static class InvalidSignatureException extends RuntimeException {
    }

    static class InvalidPayloadException extends RuntimeException {

        final List<Map<String, String>> errors;

        InvalidPayloadException(String message, List<Map<String, String>> errors) {
            super(message);
            this.errors = errors;
        }
    }
}
