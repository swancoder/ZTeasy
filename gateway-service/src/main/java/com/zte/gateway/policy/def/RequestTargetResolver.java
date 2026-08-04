package com.zte.gateway.policy.def;

/**
 * Resolves the logical target service name from a gateway request path, e.g.
 * {@code /api/v1/service-a/hello} → {@code service-a} — matching
 * {@link com.zte.gateway.config.GatewayRouteConfig}'s route path prefixes.
 * Shared by {@code ZteAuthorizationFilter} and
 * {@code ServiceToServiceAuthorizationFilter} so both resolve "target service"
 * identically.
 */
public final class RequestTargetResolver {

    private RequestTargetResolver() {}

    public static String targetService(String path) {
        String[] segments = path.split("/");
        // path = "/api/v1/service-a/hello" -> segments = ["", "api", "v1", "service-a", "hello"]
        if (segments.length >= 4 && "api".equals(segments[1]) && "v1".equals(segments[2])) {
            return segments[3];
        }
        return path;
    }
}
