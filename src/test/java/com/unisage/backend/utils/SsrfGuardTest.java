package com.unisage.backend.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    private SsrfGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SsrfGuard();
        ReflectionTestUtils.setField(guard, "allowlistRaw", "");
    }

    // ── syntax vectors — plan.md "SSRF policy" / contracts/ssrf-url-vectors.json ──

    @ParameterizedTest
    @ValueSource(strings = {
            "https://u:p@api.openai.com/v1",
            "https://@api.openai.com",
            "https://api.openai.com/v1?x=1",
            "https://api.openai.com/v1#f",
            "https://api.openai.com:0/v1",
            "https://api.openai.com:65536",
            "https://api.openai.com:8a/v1",
            "ftp://h/",
            "file:///etc/passwd",
            "gopher://h/",
            "https://api.openai.com/v1\n",
            "https://api.open ai.com",
            "https://api.openai.com\\@evil.com/",
            "https://ex%00ample.com",
            "https://xn--.com",
    })
    void syntaxRejected(String url) {
        assertThatThrownBy(() -> guard.validateSyntax(url))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_URL_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.openai.com:/v1",
            "https://münchen.de/v1",
            "https://api.openai.com./v1",
    })
    void syntaxAccepted(String url) {
        assertThatCode(() -> guard.validateSyntax(url)).doesNotThrowAnyException();
    }

    @Test
    void trailingDot_isStrippedFromHost() {
        assertThat(guard.validateSyntax("https://api.openai.com./v1")).isEqualTo("api.openai.com");
    }

    @Test
    void idna_convertsToALabel() {
        assertThat(guard.validateSyntax("https://münchen.de/v1")).isEqualTo("xn--mnchen-3ya.de");
    }

    @Test
    void urlOverMaxLength_rejected() {
        String longUrl = "https://api.openai.com/" + "a".repeat(2048);
        assertThatThrownBy(() -> guard.validateSyntax(longUrl)).isInstanceOf(AppException.class);
    }

    // ── resolved-IP range vectors — real InetAddress resolution for numeric literals,
    //    fake resolver only for the one real-DNS-name vector ──────────────────────

    static Stream<Arguments> blockedHosts() {
        return Stream.of(
                Arguments.of("127.0.0.1"),
                Arguments.of("2130706433"),
                Arguments.of("169.254.169.254"),
                Arguments.of("10.0.0.1"),
                Arguments.of("100.64.0.1")
        );
    }

    @ParameterizedTest
    @MethodSource("blockedHosts")
    void resolvedIp_inBlockedRange_rejected(String host) {
        assertThatThrownBy(() -> guard.validate("https://" + host + "/v1"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_URL_NOT_ALLOWED);
    }

    @Test
    void metadataHostname_rejected_viaFakeResolver() {
        ReflectionTestUtils.setField(guard, "dnsResolver", (SsrfGuard.DnsResolver) host -> {
            if (host.equals("metadata.google.internal")) {
                return new InetAddress[]{InetAddress.getByName("169.254.169.254")};
            }
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> guard.validate("http://metadata.google.internal/"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void ipv6Loopback_rejected() {
        assertThatThrownBy(() -> guard.validate("https://[::1]/v1")).isInstanceOf(AppException.class);
    }

    @Test
    void ipv4MappedIpv6Loopback_rejected() {
        assertThatThrownBy(() -> guard.validate("https://[::ffff:127.0.0.1]/v1")).isInstanceOf(AppException.class);
    }

    @Test
    void uniqueLocalIpv6_rejected() {
        assertThatThrownBy(() -> guard.validate("https://[fd00::1]/v1")).isInstanceOf(AppException.class);
    }

    @Test
    void publicHost_passesThrough() {
        ReflectionTestUtils.setField(guard, "dnsResolver",
                (SsrfGuard.DnsResolver) host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});

        assertThatCode(() -> guard.validate("https://api.openai.com/v1")).doesNotThrowAnyException();
    }

    @Test
    void allowlistedHost_bypassesBlockedRange() {
        ReflectionTestUtils.setField(guard, "allowlistRaw", "internal-llm.corp");
        ReflectionTestUtils.setField(guard, "dnsResolver",
                (SsrfGuard.DnsResolver) host -> new InetAddress[]{InetAddress.getByName("10.0.5.5")});

        assertThatCode(() -> guard.validate("http://internal-llm.corp/v1")).doesNotThrowAnyException();
    }

    @Test
    void sameHost_blockedWhenNotAllowlisted() {
        ReflectionTestUtils.setField(guard, "allowlistRaw", "");
        ReflectionTestUtils.setField(guard, "dnsResolver",
                (SsrfGuard.DnsResolver) host -> new InetAddress[]{InetAddress.getByName("10.0.5.5")});

        assertThatThrownBy(() -> guard.validate("http://internal-llm.corp/v1")).isInstanceOf(AppException.class);
    }

    @Test
    void dnsResolutionFailure_rejected() {
        ReflectionTestUtils.setField(guard, "dnsResolver", (SsrfGuard.DnsResolver) host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> guard.validate("https://does-not-resolve.invalid/v1"))
                .isInstanceOf(AppException.class);
    }
}
