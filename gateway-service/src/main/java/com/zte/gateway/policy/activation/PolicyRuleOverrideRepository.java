package com.zte.gateway.policy.activation;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** Reactive access to {@code policy_rule_overrides} (Stage 31, ADR-031). */
@Repository
public interface PolicyRuleOverrideRepository extends ReactiveCrudRepository<PolicyRuleOverride, String> {

    /**
     * Insert-or-update by rule id. Same not-{@code @Modifying} RETURNING
     * convention as {@code IdpIdentityRepository.upsert} (see its Javadoc).
     */
    @Query("""
            INSERT INTO policy_rule_overrides (rule_id, enabled, updated_by, updated_at)
            VALUES (:ruleId, :enabled, :updatedBy, NOW())
            ON CONFLICT (rule_id)
            DO UPDATE SET enabled = :enabled, updated_by = :updatedBy, updated_at = NOW()
            RETURNING rule_id
            """)
    Mono<String> upsert(@Param("ruleId") String ruleId, @Param("enabled") boolean enabled,
                        @Param("updatedBy") String updatedBy);
}
