package com.unisage.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void emptyList_isEmpty() {
        assertThat(CidrMatcher.parse("").isEmpty()).isTrue();
        assertThat(CidrMatcher.parse(null).isEmpty()).isTrue();
    }

    @Test
    void singleMalformedBlock_invalidatesWholeList() {
        CidrMatcher matcher = CidrMatcher.parse("127.0.0.1/32,10.0.0.0/33");

        assertThat(matcher.isEmpty()).isTrue();
        assertThat(matcher.matches("127.0.0.1")).isFalse();
    }

    @Test
    void ipv4_matchesInsideBlock_rejectsOutside() {
        CidrMatcher matcher = CidrMatcher.parse("172.16.0.0/12");

        assertThat(matcher.matches("172.16.5.5")).isTrue();
        assertThat(matcher.matches("172.31.255.254")).isTrue();
        assertThat(matcher.matches("172.32.0.1")).isFalse();
        assertThat(matcher.matches("10.0.0.1")).isFalse();
    }

    @Test
    void ipv4_exactHost32() {
        CidrMatcher matcher = CidrMatcher.parse("127.0.0.1/32");

        assertThat(matcher.matches("127.0.0.1")).isTrue();
        assertThat(matcher.matches("127.0.0.2")).isFalse();
    }

    @Test
    void ipv6_matches() {
        CidrMatcher matcher = CidrMatcher.parse("::1/128");

        assertThat(matcher.matches("::1")).isTrue();
        assertThat(matcher.matches("::2")).isFalse();
    }

    @Test
    void ipv4AddressNeverMatchesIpv6Block_andViceVersa() {
        CidrMatcher matcher = CidrMatcher.parse("::1/128");

        assertThat(matcher.matches("127.0.0.1")).isFalse();
    }
}
