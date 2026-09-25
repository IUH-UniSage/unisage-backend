package com.unisage.backend.utils;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.security.CidrMatcher;

/**
 * Validates a `ChatModel.apiBaseUrl` before it's ever saved — syntax first, then DNS + IP range,
 * matching plan.md "SSRF policy". This is a first layer (admin-input time); the real defense
 * against DNS rebinding is Python's PinnedNetworkBackend at request time.
 */
@Component
public class SsrfGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern CONTROL_OR_SPACE = Pattern.compile("[\\x00-\\x1f\\x7f\\s]");

    // Loopback, RFC1918 private, link-local (incl. cloud metadata 169.254.169.254), CGNAT,
    // unique-local v6, multicast, "this network", and IANA reserved/test ranges.
    private static final CidrMatcher BLOCKED_RANGES = CidrMatcher.parse(String.join(",",
            "127.0.0.0/8", "::1/128",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "fe80::/10",
            "100.64.0.0/10",
            "fd00::/8",
            "224.0.0.0/4", "ff00::/8",
            "0.0.0.0/8",
            "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
            "240.0.0.0/4"));

    /** Injectable so tests don't need real DNS — defaults to {@link InetAddress#getAllByName}. */
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private DnsResolver dnsResolver = InetAddress::getAllByName;

    @Value("${app.model-registry.url-allowlist:}")
    private String allowlistRaw;

    void setDnsResolver(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    /** @throws AppException(CHAT_MODEL_URL_NOT_ALLOWED) with a machine-readable reason on reject. */
    public void validate(String urlString) {
        String host = validateSyntax(urlString);
        validateResolvedAddresses(host);
    }

    /** Syntax-only pass — no network. Returns the validated (IDNA A-label, trailing-dot-stripped) host. */
    String validateSyntax(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw reject("URL_EMPTY");
        }
        if (urlString.length() > MAX_URL_LENGTH) {
            throw reject("URL_TOO_LONG");
        }
        if (CONTROL_OR_SPACE.matcher(urlString).find() || urlString.contains("\\")) {
            throw reject("CONTROL_CHAR_OR_WHITESPACE");
        }

        // java.net.URI doesn't reject a non-ASCII host outright (e.g. "münchen.de") — it silently
        // leaves getHost() null instead. Pre-convert the host to its IDNA A-label whenever the URL
        // isn't pure ASCII, before URI ever sees it, rather than trying to detect that after the fact.
        String toParse = isAscii(urlString) ? urlString : tryPunycodeHost(urlString);
        if (toParse == null) {
            throw reject("URL_SYNTAX_INVALID");
        }

        URI uri;
        try {
            uri = new URI(toParse);
        } catch (URISyntaxException e) {
            throw reject("URL_SYNTAX_INVALID");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw reject("SCHEME_NOT_ALLOWED");
        }
        if (uri.getRawUserInfo() != null) {
            throw reject("USERINFO_NOT_ALLOWED");
        }
        if (uri.getRawQuery() != null) {
            throw reject("QUERY_NOT_ALLOWED");
        }
        if (uri.getRawFragment() != null) {
            throw reject("FRAGMENT_NOT_ALLOWED");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("HOST_EMPTY");
        }

        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw reject("PORT_OUT_OF_RANGE");
        }
        // A malformed port segment (e.g. ":8a", trailing ":") makes URI.getHost() itself null or
        // leaves getPort() at -1 without signaling — java.net.URI silently accepts some of these,
        // so re-check the authority text directly for a colon followed by non-digits.
        String authority = uri.getRawAuthority();
        if (authority != null && authority.contains(":")) {
            String portPart = authority.substring(authority.lastIndexOf(':') + 1);
            if (!portPart.isEmpty() && !portPart.matches("\\d+")) {
                throw reject("PORT_NOT_NUMERIC");
            }
        }

        String normalizedHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String aLabelHost;
        try {
            aLabelHost = IDN.toASCII(normalizedHost);
        } catch (IllegalArgumentException e) {
            throw reject("HOST_IDNA_INVALID");
        }
        return aLabelHost;
    }

    private static final Pattern SCHEME_HOST_REST =
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/:?#]+)(.*)$");

    private boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    /** Best-effort: punycode-encode a non-ASCII host so {@link URI} can parse the rest normally. */
    private String tryPunycodeHost(String urlString) {
        var matcher = SCHEME_HOST_REST.matcher(urlString);
        if (!matcher.matches()) {
            return null;
        }
        try {
            String aLabelHost = IDN.toASCII(matcher.group(2));
            return matcher.group(1) + aLabelHost + matcher.group(3);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validateResolvedAddresses(String host) {
        boolean explicitlyAllowed = isHostAllowlisted(host);

        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw reject("DNS_RESOLUTION_FAILED");
        }
        if (addresses == null || addresses.length == 0) {
            throw reject("DNS_RESOLUTION_FAILED");
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address) && !explicitlyAllowed) {
                throw reject("RESOLVED_IP_BLOCKED");
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        return BLOCKED_RANGES.matches(unwrapIpv4Mapped(address).getHostAddress());
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) must be checked as its embedded IPv4 address, not as v6. */
    private InetAddress unwrapIpv4Mapped(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    isMapped = false;
                    break;
                }
            }
            isMapped = isMapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (isMapped) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return InetAddress.getByAddress(v4);
                } catch (UnknownHostException e) {
                    // 4-byte array is always a valid IPv4 address — unreachable.
                }
            }
        }
        return address;
    }

    /** Exact hostname match (case-insensitive) — a separate, explicit opt-in for private/blocked ranges. */
    private boolean isHostAllowlisted(String host) {
        if (allowlistRaw == null || allowlistRaw.isBlank()) {
            return false;
        }
        for (String entry : allowlistRaw.split(",")) {
            if (entry.trim().equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    private AppException reject(String reason) {
        return new AppException(ErrorCode.CHAT_MODEL_URL_NOT_ALLOWED, Map.of("reason", reason));
    }
}
