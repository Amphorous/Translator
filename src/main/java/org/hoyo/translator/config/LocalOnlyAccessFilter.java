package org.hoyo.translator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Restricts /admin/** endpoints to requests originating from the machine this
 * service runs on (e.g. Postman/Swagger hitting localhost directly). Requests
 * arriving via the Aquila gateway either come from Aquila's host (not loopback)
 * or carry gateway-added headers (Aquila-*, X-Forwarded-*, Forwarded), so
 * either condition is enough to reject the request here.
 */
@Component
public class LocalOnlyAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyAccessFilter.class);

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private static final Set<String> GATEWAY_HEADER_PREFIXES = Set.of(
            "aquila-",
            "x-forwarded-",
            "forwarded"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String remoteAddr = request.getRemoteAddr();
        if (!LOOPBACK_ADDRESSES.contains(remoteAddr)) {
            log.warn("Rejected non-local request to [{}] from [{}]", request.getRequestURI(), remoteAddr);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This endpoint is only accessible from the local machine.");
            return;
        }

        String gatewayHeader = findGatewayHeader(request);
        if (gatewayHeader != null) {
            log.warn("Rejected request to [{}] carrying gateway header [{}]", request.getRequestURI(), gatewayHeader);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This endpoint is only accessible from the local machine.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String findGatewayHeader(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return null;
        }
        for (String headerName : Collections.list(headerNames)) {
            String lower = headerName.toLowerCase();
            for (String prefix : GATEWAY_HEADER_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return headerName;
                }
            }
        }
        return null;
    }
}
