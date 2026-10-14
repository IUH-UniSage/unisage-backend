package com.unisage.backend.service.backup;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.unisage.backend.integration.QdrantSnapshotClient;
import com.unisage.backend.integration.QdrantSnapshotClient.BodyHandler;
import com.unisage.backend.integration.QdrantSnapshotClient.Snapshot;
import com.unisage.backend.repository.BackupRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantSnapshotServiceTest {

    private static final byte[] BODY = "snapshot bytes".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "qdrant/unisage_chunks/s1.snapshot";

    private final QdrantSnapshotClient client = mock(QdrantSnapshotClient.class);
    private final BackupStorage storage = mock(BackupStorage.class);

    private final QdrantSnapshotService service = new QdrantSnapshotService(
            client, storage, mock(BackupRunRepository.class), mock(BackupRunRecorder.class));

    @Test
    void snapshot_uploadsWithMatchingChecksum_andDeletesItFromQdrant() throws Exception {
        givenSnapshot(sha256(BODY));

        BackupArtifact artifact = service.snapshot("unisage_chunks");

        assertThat(artifact.storagePrefix()).isEqualTo(KEY);
        assertThat(artifact.checksum()).isEqualTo(sha256(BODY));
        verify(storage).upload(any(), eq((long) BODY.length), eq(KEY));
        verify(client).deleteSnapshot("unisage_chunks", "s1.snapshot");
    }

    @Test
    void snapshot_checksumMismatch_removesTheUploadAndFails() throws Exception {
        givenSnapshot("0".repeat(64));

        assertThatThrownBy(() -> service.snapshot("unisage_chunks"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("checksum mismatch");
        verify(storage).deletePrefix(KEY);
        verify(client).deleteSnapshot("unisage_chunks", "s1.snapshot");
    }

    @SuppressWarnings("unchecked")
    private void givenSnapshot(String checksum) throws Exception {
        when(client.createSnapshot("unisage_chunks")).thenReturn(new Snapshot("s1.snapshot", BODY.length, checksum));
        when(client.download(eq("unisage_chunks"), eq("s1.snapshot"), any()))
                .thenAnswer(call -> ((BodyHandler<String>) call.getArgument(2)).handle(new ByteArrayInputStream(BODY)));
        // Drain the stream like MinIO does, so the digest sees every byte.
        doAnswer(call -> {
            ((InputStream) call.getArgument(0)).readAllBytes();
            return null;
        }).when(storage).upload(any(), anyLong(), eq(KEY));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
