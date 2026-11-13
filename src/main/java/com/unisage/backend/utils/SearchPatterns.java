package com.unisage.backend.utils;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Shared "contains" search for list filters: trimmed, case-insensitive, accent-insensitive
 * ("nguyen" finds "Nguyễn", "dao tao" finds "Đào tạo"), with the user's {@code %}, {@code _} and
 * {@code \} matched literally instead of as LIKE wildcards.
 *
 * <p>The column side goes through Postgres {@code unaccent(lower(col))} (extension enabled by
 * V43); the search text is folded the same way here in Java, so no function runs on the
 * bound parameter.
 */
public final class SearchPatterns {

    public static final char ESCAPE = '\\';

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private SearchPatterns() {
    }

    /**
     * {@code %term%} for {@code LIKE ... ESCAPE '\'}, or {@code null} when the input is blank (the
     * caller then skips the predicate).
     */
    public static String containsPattern(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String folded = fold(raw.trim());
        StringBuilder escaped = new StringBuilder(folded.length() + 2).append('%');
        for (char c : folded.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.append('%').toString();
    }

    /** Lower-cases and strips diacritics, matching what Postgres {@code unaccent(lower(x))} does. */
    public static String fold(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return COMBINING_MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    /** {@code unaccent(lower(column)) LIKE pattern ESCAPE '\'}; {@code pattern} from {@link #containsPattern}. */
    public static Predicate matches(CriteriaBuilder cb, Expression<String> column, String pattern) {
        return cb.like(cb.function("unaccent", String.class, cb.lower(column)), pattern, ESCAPE);
    }

    /**
     * OR of {@link #matches} over several columns, or {@code null} when {@code raw} is blank.
     */
    @SafeVarargs
    public static Predicate anyContains(CriteriaBuilder cb, String raw, Expression<String>... columns) {
        String pattern = containsPattern(raw);
        if (pattern == null) {
            return null;
        }
        return cb.or(Arrays.stream(columns)
                .filter(Objects::nonNull)
                .map(column -> matches(cb, column, pattern))
                .toArray(Predicate[]::new));
    }
}
