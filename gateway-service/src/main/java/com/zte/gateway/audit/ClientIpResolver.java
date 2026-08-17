package com.zte.gateway.audit;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Resolves the audit trail's {@code client_ip} value — shared by {@code
 * RequestAuditFilter} (REST) and {@code McpProxyHandler} (MCP), which
 * previously duplicated this logic independently (still separate call sites;
 * this only extracts the actual resolution rule, not the two components'
 * different framework plumbing around it).
 *
 * <p>Prefers {@code X-Forwarded-For}'s first hop (a real proxy's signal of the
 * original client) over the raw connection's remote address. When falling
 * back to the raw address, loopback is normalized to {@code 127.0.0.1}
 * regardless of which IP family the OS actually used for that connection —
 * {@code localhost} resolves to both {@code 127.0.0.1} and {@code ::1}, and
 * which one a given dev-environment connection picks (curl vs. Python's
 * {@code requests}, even different runs of the same tool) is happenstance,
 * not a meaningful distinction; both mean "this machine." Without this, the
 * same effective caller shows up as two different strings in the Admin
 * Console depending purely on which stack that connection happened to use.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(String forwardedForHeader, InetSocketAddress remoteAddress) {
        if (forwardedForHeader != null && !forwardedForHeader.isBlank()) {
            return forwardedForHeader.split(",")[0].trim();
        }

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return null;
        }

        InetAddress address = remoteAddress.getAddress();
        return address.isLoopbackAddress() ? "127.0.0.1" : address.getHostAddress();
    }
}
