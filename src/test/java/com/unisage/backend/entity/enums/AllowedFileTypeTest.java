package com.unisage.backend.entity.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedFileTypeTest {

    @Test
    void fromExtension_resolvesWhitelistedExtensions() {
        assertThat(AllowedFileType.fromExtension("report.pdf")).contains(AllowedFileType.PDF);
        assertThat(AllowedFileType.fromExtension("notes.txt")).contains(AllowedFileType.TXT);
        assertThat(AllowedFileType.fromExtension("policy.docx")).contains(AllowedFileType.DOCX);
        assertThat(AllowedFileType.fromExtension("policy.doc")).contains(AllowedFileType.DOC);
    }

    @Test
    void fromExtension_isCaseInsensitive() {
        assertThat(AllowedFileType.fromExtension("REPORT.PDF")).contains(AllowedFileType.PDF);
    }

    @Test
    void fromExtension_rejectsUnlistedExtension() {
        assertThat(AllowedFileType.fromExtension("malware.exe")).isEmpty();
        assertThat(AllowedFileType.fromExtension("image.png")).isEmpty();
    }

    @Test
    void fromExtension_rejectsMissingOrBlankFilename() {
        assertThat(AllowedFileType.fromExtension(null)).isEmpty();
        assertThat(AllowedFileType.fromExtension("")).isEmpty();
        assertThat(AllowedFileType.fromExtension("noextension")).isEmpty();
        assertThat(AllowedFileType.fromExtension("trailingdot.")).isEmpty();
    }
}
