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

    /** The client address: the proxy-reported one if the peer is the proxy, else the TCP peer. */
    public String of(HttpServletRequest request) {
        if (fromProxy(request)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String first = forwarded.split(",")[0].strip();
                if (Cidr.isIpLiteral(first)) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
