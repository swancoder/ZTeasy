package com.zte.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the client certificate an edge proxy relayed, and verifies it against our
 * own CA (Stage 40, ADR-040).
 *
 * <p>Azure Container Apps' HTTP ingress can be told to request a client certificate
 * ({@code clientCertificateMode: Accept}) and forwards it to the app in Envoy's
 * {@code X-Forwarded-Client-Cert} header. That is what makes one front door possible
 * for both browsers and agents: ADR-028 needed two apps because TCP passthrough was
 * the only way to keep a client certificate, and TCP ingress cannot carry a custom
 * domain.
 *
 * <p><b>Why the header can be trusted, and exactly how far.</b> A client that sets
 * this header itself does not reach the app with it — measured, not assumed: a
 * forged {@code X-Forwarded-Client-Cert} sent from outside arrives absent, because
 * the edge sanitises and re-sets it from the TLS handshake. What is being trusted is
 * therefore the edge, which is inside the perimeter this gateway defends. TLS no
 * longer terminates at the gate itself, and ADR-040 says so plainly rather than
 * letting the demo keep an older claim.
 *
 * <p><b>What is NOT delegated.</b> The edge accepts any client certificate; it does
 * not know our CA. So the chain is validated here, against {@code ca.crt} — the same
 * anchor the truststore uses. An unverifiable certificate is treated as no
 * certificate at all.
 */
@Component
public class ForwardedClientCertificate {

    private static final Logger log = LoggerFactory.getLogger(ForwardedClientCertificate.class);

    /** Envoy's XFCC is a list of {@code Key=Value} pairs; the whole certificate is under {@code Cert}. */
    private static final String HEADER = "X-Forwarded-Client-Cert";

    private final String caPath;
    private final boolean enabled;

    public ForwardedClientCertificate(
            @Value("${zte.mtls.certs-dir:./certs}") String certsDir,
            @Value("${zte.mtls.forwarded-cert-enabled:true}") boolean enabled) {
        this.caPath = certsDir + "/ca.crt";
        this.enabled = enabled;
    }

    /**
     * @return the relayed certificate if one is present AND chains to our CA;
     *         empty otherwise — including when it is present but unverifiable,
     *         which is logged, because that is the interesting case.
     */
    public Optional<X509Certificate> verified(ServerWebExchange exchange) {
        if (!enabled) {
            return Optional.empty();
        }
        String header = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        return parse(header).flatMap(this::verifyAgainstOurCa);
    }

    private Optional<X509Certificate> parse(String header) {
        // Key=Value;Key="Value" — the PEM is URL-encoded inside quotes.
        for (String part : header.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("Cert")) {
                String pem = URLDecoder.decode(kv[1].trim().replaceAll("^\"|\"$", ""), StandardCharsets.UTF_8);
                try (InputStream in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
                    return Optional.of((X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(in));
                } catch (Exception e) {
                    log.warn("[ZTE-MTLS] forwarded client certificate could not be parsed: {}", e.toString());
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private Optional<X509Certificate> verifyAgainstOurCa(X509Certificate cert) {
        try (FileInputStream in = new FileInputStream(caPath)) {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            var factory = CertificateFactory.getInstance("X.509");
            var path = factory.generateCertPath(List.of(cert));
            var params = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
            params.setRevocationEnabled(false);   // no CRL/OCSP in this PKI (ADR-004)
            CertPathValidator.getInstance("PKIX").validate(path, params);
            cert.checkValidity();
            return Optional.of(cert);
        } catch (Exception e) {
            // Someone presented a certificate the edge accepted and we do not trust.
            // Worth a warning: it is either a misconfiguration or an attempt.
            log.warn("[ZTE-MTLS] forwarded client certificate '{}' does not chain to our CA: {}",
                    cert.getSubjectX500Principal(), e.toString());
            return Optional.empty();
        }
    }

    /** True when the CA file this verifier needs is actually present. */
    public boolean caAvailable() {
        return Files.isReadable(Path.of(caPath));
    }
}
