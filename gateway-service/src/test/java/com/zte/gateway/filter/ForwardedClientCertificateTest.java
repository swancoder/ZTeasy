package com.zte.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The certificate an edge proxy relays, and what we refuse to take on faith
 * (Stage 40, ADR-040).
 *
 * <p>Uses the development PKI in {@code certs/} when it exists — the same CA the
 * gateway trusts at runtime — and skips otherwise, since generating a CA inside a
 * unit test would prove something about the test's own PKI rather than about ours.
 */
class ForwardedClientCertificateTest {

    private final Path certs = Path.of("../certs");

    private ForwardedClientCertificate verifier() {
        return new ForwardedClientCertificate(certs.toString(), true);
    }

    private MockServerWebExchange withHeader(String value) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/sse")
                .header("X-Forwarded-Client-Cert", value).build());
    }

    private String xfcc(Path pemFile) throws Exception {
        String pem = Files.readString(pemFile);
        return "Hash=abc;Cert=\"" + URLEncoder.encode(pem, StandardCharsets.UTF_8) + "\"";
    }

    @Test
    void aCertificateFromOurCa_isAccepted() throws Exception {
        assumeTrue(Files.isReadable(certs.resolve("client.crt")), "dev PKI not generated");

        assertThat(verifier().verified(withHeader(xfcc(certs.resolve("client.crt")))))
                .isPresent();
    }

    /**
     * The edge accepts any client certificate — it has never heard of our CA. So the
     * signature has to be checked here, and this proves it is: the same certificate
     * with one byte of its signature flipped still parses, still names the same
     * subject, and must still be refused.
     *
     * <p>Without this check, "presented a certificate" would mean "presented any
     * certificate", which is not authentication.
     */
    @Test
    void aCertificateWhoseSignatureDoesNotVerify_isRefused() throws Exception {
        assumeTrue(Files.isReadable(certs.resolve("client.crt")), "dev PKI not generated");

        String pem = Files.readString(certs.resolve("client.crt"));
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(body);
        der[der.length - 1] ^= 0x01;                    // last byte lives in the signature
        String tampered = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";

        assertThat(verifier().verified(withHeader(
                "Hash=abc;Cert=\"" + URLEncoder.encode(tampered, StandardCharsets.UTF_8) + "\"")))
                .isEmpty();
    }

    @Test
    void garbageInTheHeader_isRefusedRatherThanCrashing() {
        assertThat(verifier().verified(withHeader("Hash=abc;Cert=\"not-a-certificate\""))).isEmpty();
        assertThat(verifier().verified(withHeader("nonsense"))).isEmpty();
        assertThat(verifier().verified(withHeader(""))).isEmpty();
    }

    @Test
    void noHeaderAtAll_meansNoCertificate() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/sse").build());
        assertThat(verifier().verified(exchange)).isEmpty();
    }

    /** Switched off, the relay is ignored entirely — the direct TLS path is unaffected. */
    @Test
    void disabled_ignoresEvenAValidCertificate() throws Exception {
        assumeTrue(Files.isReadable(certs.resolve("client.crt")), "dev PKI not generated");

        var off = new ForwardedClientCertificate(certs.toString(), false);
        assertThat(off.verified(withHeader(xfcc(certs.resolve("client.crt"))))).isEmpty();
    }
}
