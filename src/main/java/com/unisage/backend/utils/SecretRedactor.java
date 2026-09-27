package com.unisage.backend.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secrets from arbitrary text (provider exception messages, health-report bodies,
 * anything that might end up in a DB column / Slack payload / HTTP response) before it leaves
 * the process. Matches plan.md "Secret redaction" — must run identically to the Python side
 * (`app/core/redaction.py`), and the shared vector file at
 * `unisage-backend/contracts/redaction-vectors.json` is the single source of truth both repos
 * test against.
 *
 * <p>Order matters: redact the exact known secret (and its substrings ≥ 8 chars) first, then the
 * generic header/prefix/query/userinfo patterns, and only then truncate to 500 chars — truncating
 * first could split a secret exactly at the boundary and let half of it survive.
 */
public final class SecretRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_LENGTH = 500;
    private static final int MIN_SUBSTRING_LENGTH = 8;

    // Authorization: <scheme> <token> — case-insensitive header name, requires the colon so
    // unrelated identifiers like "AuthorizationError" are never matched; stops at
    // whitespace/quote/comma/end so it doesn't eat trailing prose.
    private static final Pattern AUTHORIZATION_HEADER =
            Pattern.compile("(?i)Authorization\\s*:\\s*(Bearer\\s+)?[A-Za-z0-9._~+/-]+=*");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    // x-api-key / api-key header or JSON-ish field, with optional colon/equals separator and
    // optional quoting.
    private static final Pattern API_KEY_HEADER =
            Pattern.compile("(?i)(x-api-key|api-key)\\s*[:=]\\s*\"?[A-Za-z0-9._~+/-]+=*\"?");
    // Raw provider key prefixes (OpenAI-style `sk-...`, Anthropic `sk-ant-...`). Match the prefix
    // plus the following token run so the key body is swallowed, not just the prefix.
    private static final Pattern RAW_KEY_PREFIX =
            Pattern.compile("sk-ant-[A-Za-z0-9._-]+|sk-[A-Za-z0-9._-]+");
    // Query params: key=, api_key=, token= up to the next & / whitespace / quote.
    private static final Pattern QUERY_PARAM =
            Pattern.compile("(?i)([?&])(key|api_key|token)=[^&\\s\"'#]*");
    // URL userinfo: scheme://user:pass@ — redact the user:pass portion, keep scheme:// and host.
    private static final Pattern URL_USERINFO =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@");

    private SecretRedactor() {
    }

    /**
     * Redacts {@code text} against the generic patterns only (no known secret to match exactly),
     * then truncates to 500 chars. Prefer {@link #redact(String, String)} whenever the credential
     * whose secret might appear is known.
     */
    public static String redact(String text) {
        return redact(text, null);
    }

    /**
     * @param text      raw text that may contain a secret (exception message, response body, ...).
     * @param knownSecret the exact API key of the credential currently being processed, or
     *                    {@code null}/blank if not applicable. Redacted first, along with any of
     *                    its substrings of length ≥ 8 — the strongest, format-independent layer.
     * @return redacted text, truncated to 500 chars. Never {@code null} (empty string for
     *         {@code null}/blank input).
     */
    public static String redact(String text, String knownSecret) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;
        if (knownSecret != null && !knownSecret.isBlank()) {
            result = redactKnownSecret(result, knownSecret);
        }
        result = redactPatterns(result);

        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH);
        }
        return result;
    }

    /**
     * Replaces the exact secret, and every contiguous substring of it that is ≥ 8 chars, with
     * {@link #REDACTED}. Rather than enumerating every substring length (quadratic), this relies
     * on the fact that any surviving substring of length ≥ 8 must contain at least one
     * length-{@value #MIN_SUBSTRING_LENGTH} sliding window of the secret aligned to the same
     * offsets — so redacting every such window is sufficient to guarantee no ≥8-char fragment
     * survives, in linear-in-secret-length passes.
     */
    private static String redactKnownSecret(String text, String secret) {
        String trimmed = secret.strip();
        if (trimmed.isEmpty()) {
            return text;
        }
        if (trimmed.length() < MIN_SUBSTRING_LENGTH) {
            // Too short to safely window-match (would nuke unrelated short text) — still redact
            // the exact secret itself; the generic patterns below cover the rest.
            return text.contains(trimmed) ? text.replace(trimmed, REDACTED) : text;
        }

        String result = text.contains(secret) ? text.replace(secret, REDACTED) : text;
        for (int start = 0; start + MIN_SUBSTRING_LENGTH <= trimmed.length(); start++) {
            String window = trimmed.substring(start, start + MIN_SUBSTRING_LENGTH);
            if (result.contains(window)) {
                result = result.replace(window, REDACTED);
            }
        }
        return result;
    }

    private static String redactPatterns(String text) {
        String result = text;
        result = replaceAll(AUTHORIZATION_HEADER, result);
        result = replaceAll(BEARER_TOKEN, result);
        result = replaceAll(API_KEY_HEADER, result);
        result = replaceAll(RAW_KEY_PREFIX, result);
        result = replaceQueryParams(result);
        result = replaceAll(URL_USERINFO, result, "$1" + REDACTED + "@");
        return result;
    }

    private static String replaceQueryParams(String text) {
        Matcher matcher = QUERY_PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "=" + REDACTED));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String replaceAll(Pattern pattern, String text) {
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private static String replaceAll(Pattern pattern, String text, String replacement) {
        return pattern.matcher(text).replaceAll(replacement);
    }
}
