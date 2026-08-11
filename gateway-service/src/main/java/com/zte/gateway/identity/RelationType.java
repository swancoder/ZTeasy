package com.zte.gateway.identity;

/**
 * The two relation kinds {@code idp_identity_relations} tracks (ADR-016):
 * a user's group membership, and a user's or client's realm role
 * assignment.
 */
public enum RelationType {
    MEMBER_OF, HAS_ROLE
}
