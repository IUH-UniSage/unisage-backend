package com.unisage.backend.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPatternsTest {

    @Test
    void foldsCaseAndVietnameseDiacritics() {
        assertThat(SearchPatterns.fold("Nguyễn Văn Đức")).isEqualTo("nguyen van duc");
        assertThat(SearchPatterns.fold("PHÒNG ĐÀO TẠO")).isEqualTo("phong dao tao");
    }

    @Test
    void trimsAndWrapsInWildcards() {
        assertThat(SearchPatterns.containsPattern("  Khoa CNTT ")).isEqualTo("%khoa cntt%");
    }

    @Test
    void escapesLikeWildcardsAndTheEscapeChar() {
        assertThat(SearchPatterns.containsPattern("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    void blankInputMeansNoFilter() {
        assertThat(SearchPatterns.containsPattern(null)).isNull();
        assertThat(SearchPatterns.containsPattern("   ")).isNull();
    }
}
