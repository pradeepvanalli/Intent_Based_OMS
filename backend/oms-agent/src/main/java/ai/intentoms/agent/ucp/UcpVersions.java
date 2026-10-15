package ai.intentoms.agent.ucp;

import ai.intentoms.platform.error.OmsException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * UCP version negotiation (FR-AGT-02). UCP is versioned by date; the order capability implemented here is
 * {@value #DEFAULT}. A missing {@code UCP-Version} header selects the default; an unsupported one is a 400 listing
 * {@code supported_versions} so adapters negotiate instead of hard-coding.
 */
public final class UcpVersions {
    public static final String HEADER = "UCP-Version";
    public static final String DEFAULT = "2026-08-25";
    public static final List<String> SUPPORTED = List.of(DEFAULT);

    private UcpVersions() {}

    public static String negotiate(String header) {
        if (header == null || header.isBlank()) return DEFAULT;
        String v = header.trim();
        if (SUPPORTED.contains(v)) return v;
        throw new OmsException(HttpStatus.BAD_REQUEST, "unsupported_ucp_version", "UCP version " + v + " is not supported", Map.of("supported_versions", SUPPORTED, "requested", v), null);
    }
}
