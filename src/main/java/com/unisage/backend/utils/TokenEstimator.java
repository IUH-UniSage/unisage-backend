package com.unisage.backend.utils;

import java.text.Normalizer;

import org.springframework.stereotype.Component;

/**
 * Estimates how many tokens a piece of chat text costs against a usage limit.
 *
 * <p>This is deliberately a self-contained estimate, not the LLM's real token count: the same text
 * always costs the same, regardless of which model answers. Each run of letters/digits (a "word")
 * costs {@code ceil(codePoints / 3)}; every other non-whitespace character costs one token.
 * Text is normalised to NFC first so composed and decomposed Vietnamese diacritics count the same.
 */
@Component
public class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 3;

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        int tokens = 0;
        int wordLength = 0;

        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);

            if (isWordCharacter(codePoint)) {
                wordLength++;
                continue;
            }
            tokens += wordCost(wordLength);
            wordLength = 0;
            if (!Character.isWhitespace(codePoint)) {
                tokens++;
            }
        }
        return tokens + wordCost(wordLength);
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || Character.getType(codePoint) == Character.NON_SPACING_MARK;
    }

    private static int wordCost(int wordLength) {
        return (wordLength + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
