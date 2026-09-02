package com.zte.gateway.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * E2E test for ADR-014's IdP identity sync — this is what actually proves
 * {@code zte-gateway}'s service account really has the {@code view-users}/
 * {@code view-realm}/{@code view-clients} (ADR-015) realm-management roles
 * granted in {@code keycloak/realm-export.json} (Part A of the plan flagged
 * this as the highest-risk, least unit-testable piece), not just a hope.
 *
 * <p>Chain exercised: ADMIN JWT → {@code POST /api/v1/admin/identities/sync}
 * → {@code KeycloakIdpAdapter} → real Testcontainers Keycloak Admin REST API
 * → {@code idp_identities} (real Postgres) → {@code GET
 * /api/v1/admin/identities/search}.
 */
@DisplayName("IdP Identity Sync — real Keycloak Admin API round trip")
class IdentitySyncIT extends BaseZteIntegrationTest {

    @Test
    @DisplayName("Manual sync populates idp_identities with realm roles and users")
    void manualSync_populatesRolesAndUsers() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .post("/api/v1/admin/identities/sync")
        .then()
            .statusCode(200)
            // ADMIN/USER roles + zte-admin/zte-test-user users + at least
            // zte-gateway/crm-account-health-emea-01/zte-admin-ui clients, at least.
            .body("synced", greaterThanOrEqualTo(8));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .queryParam("type", "ROLE")
        .when()
            .get("/api/v1/admin/identities/search")
        .then()
            .statusCode(200)
            .body("name", hasItem("ADMIN"))
            .body("name", hasItem("USER"));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .queryParam("type", "USER")
        .when()
            .get("/api/v1/admin/identities/search")
        .then()
            .statusCode(200)
            .body("name", hasItem(ADMIN_USERNAME))
            .body("name", hasItem(USER_USERNAME));
    }

    @Test
    @DisplayName("Manual sync populates idp_identities with OIDC clients (ADR-015)")
    void manualSync_populatesClients() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .post("/api/v1/admin/identities/sync")
        .then()
            .statusCode(200);

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .queryParam("type", "CLIENT")
        .when()
            .get("/api/v1/admin/identities/search")
        .then()
            .statusCode(200)
            .body("name", hasItem("crm-account-health-emea-01"))
            .body("name", hasItem("zte-gateway"));
    }

    @Test
    @DisplayName("Manual sync excludes Keycloak system clients (ADR-016)")
    void manualSync_excludesSystemClients() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .post("/api/v1/admin/identities/sync")
        .then()
            .statusCode(200);

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .queryParam("type", "CLIENT")
        .when()
            .get("/api/v1/admin/identities/search")
        .then()
            .statusCode(200)
            .body("name", not(hasItem("account")))
            .body("name", not(hasItem("account-console")))
            .body("name", not(hasItem("broker")))
            .body("name", not(hasItem("realm-management")))
            .body("name", not(hasItem("admin-cli")))
            .body("name", not(hasItem("security-admin-console")));
    }

    @Test
    @DisplayName("Relations endpoint reflects synced role assignments, reading only the local cache (ADR-016)")
    void manualSync_thenRelationsEndpoint_reflectsRoleAssignment() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .post("/api/v1/admin/identities/sync")
        .then()
            .statusCode(200);

        String adminUserId =
            given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + token)
                .queryParam("type", "USER")
                .queryParam("q", ADMIN_USERNAME)
            .when()
                .get("/api/v1/admin/identities/search")
            .then()
                .statusCode(200)
                .extract().path("find { it.name == '" + ADMIN_USERNAME + "' }.id");

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/api/v1/admin/identities/" + adminUserId + "/relations")
        .then()
            .statusCode(200)
            .body("findAll { it.relationType == 'HAS_ROLE' }.name", hasItem("ADMIN"));
    }

    @Test
    @DisplayName("USER role — 403, sync never triggered")
    void nonAdminUser_cannotTriggerSync() {
        String token = getUserToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
        .when()
            .post("/api/v1/admin/identities/sync")
        .then()
            .statusCode(403);
    }

    @Test
    @DisplayName("Search with an unknown type filter returns an empty list, not an error")
    void search_unknownTypeFilter_returnsEmpty() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .queryParam("type", "NOT_A_TYPE")
        .when()
            .get("/api/v1/admin/identities/search")
        .then()
            .statusCode(200)
            .body("size()", equalTo(0));
    }
}
