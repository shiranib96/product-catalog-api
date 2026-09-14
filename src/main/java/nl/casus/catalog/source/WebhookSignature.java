package nl.casus.catalog.source;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

// The source signs every webhook call with a secret we both know: X-Signature: sha256=<hex hmac of the body>.
// Without it anyone who finds the endpoint could change our prices.
@Component
class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;

    WebhookSignature(SourceProperties properties) {
        this.key = new SecretKeySpec(properties.webhook().secret().getBytes(UTF_8), ALGORITHM);
    }

    boolean matches(byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // constant time compare, so the signature can't be guessed byte by byte from response times
        return MessageDigest.isEqual(sign(body), given);
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not calculate " + ALGORITHM, e);
        }
    }
}
