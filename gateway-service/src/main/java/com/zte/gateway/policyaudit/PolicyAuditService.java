package com.zte.gateway.policyaudit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.policy.activation.PolicyActivationStore;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs and reads AI policy audits (Stage 31, ADR-031).
 *
 * <p>The run PUSHES the current policy document to {@code zt-agents}' new
 * {@code /analyze} endpoint — reversing ADR-007's fetch-from-gateway flow —
 * because in the cloud topology (ADR-027) zt-agents does not trust the
 * gateway's dev CA, so the old direction fails TLS there. Pushing removes
 * that dependency entirely: this call rides the default {@code
 * WebClient.Builder} like every other outbound gateway call.
 *
 * <p>Per-referenced-rule content hashes are captured at run time; freshness
 * is computed at read time by {@link FreshnessEvaluator} — see its Javadoc
 * for why it is never stored.
 */
@Service
public class PolicyAuditService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-POLICY-AUDIT");
    private static final TypeReference<List<AuditFinding>> FINDINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> HASHES = new TypeReference<>() {};

    private final PolicyDefinitionStore policyStore;
    private final PolicyActivationStore activationStore;
    private final PolicyAuditRunRepository repository;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Duration timeout;

    public PolicyAuditService(PolicyDefinitionStore policyStore,
                              PolicyActivationStore activationStore,
                              PolicyAuditRunRepository repository,
                              ObjectMapper objectMapper,
                              WebClient.Builder webClientBuilder,
                              @Value("${zt-agents.uri:http://localhost:8083}") String ztAgentsUri,
                              @Value("${zt-agents.audit-timeout-seconds:180}") long timeoutSeconds) {
        this.policyStore = policyStore;
        this.activationStore = activationStore;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // A dedicated connector: the default builder's Netty client carries a
        // 30s response timeout sized for service-to-service hops, which an LLM
        // review legitimately exceeds. zt-agents is plain HTTP inside the
        // perimeter, so dropping the shared (mTLS-carrying) connector costs
        // nothing here.
        this.webClient = webClientBuilder.clone()
                .baseUrl(ztAgentsUri)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(this.timeout)))
                .build();
    }

    /** Executes an audit against the current document and persists the run. */
    public Mono<PolicyAuditRunView> run(String requestedBy) {
        JsonNode policies = objectMapper.valueToTree(policyStore.current());
        log.info("[ZTE-POLICY-AUDIT] run requested by {} ({} rules)", requestedBy,
                policyStore.current().allRules().size());
        return webClient.post()
                .uri("/api/v1/agents/auditor/analyze")
                .bodyValue(Map.of("policies", policies))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .flatMap(response -> persist(requestedBy, response))
                .map(this::toView)
                .doOnError(e -> log.warn("[ZTE-POLICY-AUDIT] run failed: {}", e.toString()));
    }

    private Mono<PolicyAuditRun> persist(String requestedBy, JsonNode response) {
        try {
            List<AuditFinding> findings = new ArrayList<>();
            JsonNode rawFindings = response.path("findings");
            int i = 0;
            for (JsonNode node : rawFindings) {
                AuditFinding parsed = objectMapper.treeToValue(node, AuditFinding.class);
                findings.add(new AuditFinding("f-" + (++i), parsed.severity(), parsed.title(),
                        parsed.ruleIds(), parsed.recommendation(), parsed.suggestedAction(),
                        parsed.suggestedYaml(), null, null));
            }
            boolean parseError = response.path("parseError").asBoolean(false);

            Map<String, PolicyRule> byId = currentRulesById();
            Map<String, String> hashes = findings.stream()
                    .flatMap(f -> f.ruleIds() == null ? java.util.stream.Stream.<String>empty() : f.ruleIds().stream())
                    .distinct()
                    .filter(byId::containsKey)
                    .collect(Collectors.toMap(Function.identity(), id -> FreshnessEvaluator.hash(byId.get(id))));

            PolicyAuditRun run = new PolicyAuditRun(null, Instant.now(), requestedBy,
                    response.path("model").asText(null),
                    parseError ? "PARSE_ERROR" : "COMPLETED",
                    response.path("raw").asText(null),
                    objectMapper.writeValueAsString(findings),
                    objectMapper.writeValueAsString(hashes));
            return repository.save(run)
                    .doOnNext(saved -> log.info("[ZTE-POLICY-AUDIT] run {} stored: {} findings, status={}",
                            saved.id(), findings.size(), saved.status()));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /** Latest run with per-finding freshness computed against the live document. */
    public Mono<PolicyAuditRunView> latest() {
        return repository.findTopByOrderByTimestampDesc().map(this::toView);
    }

    /** Marks one finding of the latest run as taken into work (the Modify button). */
    public Mono<PolicyAuditRunView> acknowledge(String findingId, String who) {
        return repository.findTopByOrderByTimestampDesc()
                .flatMap(run -> {
                    List<AuditFinding> findings = readFindings(run).stream()
                            .map(f -> f.id().equals(findingId)
                                    ? f.acknowledged(who, Instant.now().toString())
                                    : f)
                            .toList();
                    try {
                        PolicyAuditRun updated = new PolicyAuditRun(run.id(), run.timestamp(), run.requestedBy(),
                                run.model(), run.status(), run.rawReport(),
                                objectMapper.writeValueAsString(findings), run.ruleHashesJson());
                        return repository.save(updated);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .map(this::toView);
    }

    private PolicyAuditRunView toView(PolicyAuditRun run) {
        Map<String, PolicyRule> byId = currentRulesById();
        Map<String, String> hashesAtRun = readHashes(run);
        List<FindingView> views = readFindings(run).stream()
                .map(f -> new FindingView(f, FreshnessEvaluator.freshness(
                        f, hashesAtRun, byId, id -> !activationStore.isEnabled(id))))
                .toList();
        return new PolicyAuditRunView(run.id(), run.timestamp(), run.requestedBy(), run.model(),
                run.status(), run.rawReport(), views);
    }

    private Map<String, PolicyRule> currentRulesById() {
        return policyStore.current().allRules().stream()
                .collect(Collectors.toMap(PolicyRule::id, Function.identity(), (a, b) -> a));
    }

    private List<AuditFinding> readFindings(PolicyAuditRun run) {
        try {
            return run.findingsJson() == null ? List.of() : objectMapper.readValue(run.findingsJson(), FINDINGS);
        } catch (Exception e) {
            log.warn("[ZTE-POLICY-AUDIT] stored findings unreadable for run {}: {}", run.id(), e.toString());
            return List.of();
        }
    }

    private Map<String, String> readHashes(PolicyAuditRun run) {
        try {
            return run.ruleHashesJson() == null ? Map.of() : objectMapper.readValue(run.ruleHashesJson(), HASHES);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
