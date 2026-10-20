package de.systemlogistics.slgos.core.security;

import de.systemlogistics.slgos.core.config.Cidr;
import de.systemlogistics.slgos.core.config.SlgProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decides which peers belong to the proxy network and what the client address is. Headers set by the proxy
 * ({@code X-Forwarded-For}, {@code X-Correlation-Id}) count only when the TCP peer is inside that network.
 */
@Component
public class ClientIp {

    private final List<Cidr> proxyNetworks;

    public ClientIp(SlgProperties properties) {
        this.proxyNetworks = properties.trustedProxyCidrs().stream().map(Cidr::parse).toList();
    }

    /** True when the TCP peer of the request is in a configured proxy network. */
    public boolean fromProxy(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        return proxyNetworks.stream().anyMatch(c -> c.contains(peer));
    }

    /** The client address: the right-most non-proxy entry of {@code X-Forwarded-For} if the peer is the proxy, else the TCP peer. */
    public String of(HttpServletRequest request) {
        if (fromProxy(request)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                // A proxy may append to what the client sent, so the client-controlled entries are on the left:
                // take the right-most entry that is not itself a proxy.
                String[] entries = forwarded.split(",");
                for (int i = entries.length - 1; i >= 0; i--) {
                    String entry = entries[i].strip();
                    if (!Cidr.isIpLiteral(entry)) {
                        break;
                    }
                    if (proxyNetworks.stream().noneMatch(c -> c.contains(entry))) {
                        return entry;
                    }
                }
            }
        }
        return request.getRemoteAddr();
    }
}
