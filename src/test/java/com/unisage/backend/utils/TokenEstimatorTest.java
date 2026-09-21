package com.unisage.backend.utils;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void estimate_nullOrBlank_isZero() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate("   \n\t ")).isZero();
    }

    @Test
    void estimate_word_isCeilOfLengthOverThree() {
        assertThat(estimator.estimate("a")).isEqualTo(1);
        assertThat(estimator.estimate("abc")).isEqualTo(1);
        assertThat(estimator.estimate("abcd")).isEqualTo(2);
        assertThat(estimator.estimate("abcdefg")).isEqualTo(3);
    }

    @Test
    void estimate_sumsAcrossWords() {
        // "xin"=1, "chao"=2, "ban"=1
        assertThat(estimator.estimate("xin chao ban")).isEqualTo(4);
    }

    @Test
    void estimate_vietnameseDiacritics_countsCharactersNotBytes() {
        // "Trường"=6 chars -> 2, "Đại"=3 chars -> 1, "học"=3 chars -> 1
        assertThat(estimator.estimate("Trường Đại học")).isEqualTo(4);
    }

    @Test
    void estimate_decomposedAndComposedForms_areEqual() {
        String composed = Normalizer.normalize("Trường Đại học Công nghiệp", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);

        assertThat(estimator.estimate(decomposed)).isEqualTo(estimator.estimate(composed));
    }

    @Test
    void estimate_numbersAreWords() {
        assertThat(estimator.estimate("2025")).isEqualTo(2);
        assertThat(estimator.estimate("100")).isEqualTo(1);
    }

    @Test
    void estimate_eachPunctuationCharacterIsOneToken() {
        // "a"=1 + ","=1 + "b"=1 + "?"=1
        assertThat(estimator.estimate("a, b?")).isEqualTo(4);
    }

    @Test
    void estimate_isDeterministicAndMonotonicOnLongText() {
        String sentence = "Học phí ngành Công nghệ thông tin năm 2025 là bao nhiêu? ";
        String longText = sentence.repeat(200);

        assertThat(estimator.estimate(longText)).isEqualTo(estimator.estimate(longText));
        assertThat(estimator.estimate(longText)).isEqualTo(estimator.estimate(sentence) * 200);
    }
}
