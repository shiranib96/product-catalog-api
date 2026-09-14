package nl.casus.catalog.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// errors are returned as problem details (RFC 9457)
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    // the default 400 response doesn't say which parameter is wrong, so add the errors to it
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        var problem = exception.getBody();
        List<Map<String, String>> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> describe(error, result.getMethodParameter().getParameterName())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private static Map<String, String> describe(MessageSourceResolvable error, String name) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : name;
        return Map.of(
                "field", Objects.requireNonNullElse(field, "request"),
                "message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"));
    }
}
