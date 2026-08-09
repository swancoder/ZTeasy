package com.zte.gateway.policy.def;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parses a {@link Resource} (classpath or filesystem — either works uniformly via
 * Spring's {@code Resource} abstraction) into a {@link PolicyDocument}.
 *
 * <p>Purely synchronous — callers ({@link PolicyDefinitionStore}) are responsible
 * for keeping this off the Netty event loop (wrapped in
 * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}), matching
 * the blocking-I/O isolation pattern already used by {@code LoggingMcpAuditService}'s
 * drain loop.
 *
 * <p>Distinguishes the same three failure classes {@code PolicyDefinitionStore}
 * needs to report distinctly: file not found/unreadable, malformed YAML syntax
 * (line/column preserved), and structurally-invalid content (unknown top-level
 * keys — rejected rather than silently ignored, to avoid masking typos).
 */
@Component
public class YamlPolicyFileLoader {

    // FAIL_ON_UNKNOWN_PROPERTIES is Jackson's default (on) — kept explicit here since
    // rejecting unknown top-level keys (rather than silently ignoring typos) is a
    // deliberate schema-strictness requirement, not an accident of the default.
    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public PolicyDocument load(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new PolicyLoadException(
                    "Policy file not found or unreadable: " + describe(resource));
        }
        try (InputStream in = resource.getInputStream()) {
            PolicyDocument document = mapper.readValue(in, PolicyDocument.class);
            if (document == null) {
                throw new PolicyLoadException("Policy file is empty: " + describe(resource));
            }
            return document;
        } catch (MismatchedInputException e) {
            throw new PolicyLoadException(
                    "Policy file does not match the expected schema (" + describe(resource) + "): "
                            + e.getOriginalMessage(), e);
        } catch (IOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MarkedYAMLException yamlEx) {
                throw new PolicyLoadException(
                        "Malformed YAML in " + describe(resource) + " at " + yamlEx.getProblemMark()
                                + ": " + yamlEx.getProblem(), e);
            }
            throw new PolicyLoadException(
                    "Failed to read policy file " + describe(resource) + ": " + e.getMessage(), e);
        }
    }

    private String describe(Resource resource) {
        try {
            return resource.getDescription();
        } catch (Exception e) {
            return resource.toString();
        }
    }
}
