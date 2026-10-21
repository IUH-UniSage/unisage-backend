package com.unisage.backend.service.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;

/**
 * Backups go to their own bucket ({@code app.backup.bucket}), not the documents bucket, so a
 * lifecycle rule or access policy on one never touches the other.
 */
@Component
@RequiredArgsConstructor
public class BackupStorage {

    private static final long PART_SIZE = 64L * 1024 * 1024;

    private final MinioClient minioClient;
    /** Signs download links for the browser; the name picks the bean, as in FileServiceImpl. */
    private final MinioClient publicMinioClient;
    private final BackupProperties properties;

    private volatile boolean bucketReady;

    /** Uploads every file under {@code dir}, keyed by its path relative to {@code dir}. */
    public BackupArtifact uploadDirectory(Path dir, String prefix) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new BackupException("cannot list " + dir, e);
        }
        long size = 0;
        for (Path file : files) {
            String relative = dir.relativize(file).toString().replace('\\', '/');
            size += uploadFile(file, prefix + relative);
        }
        return new BackupArtifact(prefix, files.size(), size, null);
    }

    /** Returns the uploaded size in bytes. */
    public long uploadFile(Path file, String key) {
        ensureBucket();
        try {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(properties.getBucket()).object(key).filename(file.toString()).build());
            return Files.size(file);
        } catch (Exception e) {
            throw new BackupException("cannot upload " + key, e);
        }
    }

    public void upload(InputStream in, long size, String key) {
        ensureBucket();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket()).object(key).stream(in, size, PART_SIZE).build());
        } catch (Exception e) {
            throw new BackupException("cannot upload " + key, e);
        }
    }

    public void download(String key, Path target) {
        try {
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(properties.getBucket()).object(key).filename(target.toString()).build());
        } catch (Exception e) {
            throw new BackupException("cannot download " + key, e);
        }
    }

    /** One stored object, as listed under a prefix. */
    public record StoredObject(String key, Instant lastModified, long size) {
    }

    public List<StoredObject> list(String prefix) {
        ensureBucket();
        try {
            List<StoredObject> objects = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket()).prefix(prefix).recursive(true).build())) {
                Item item = result.get();
                objects.add(new StoredObject(item.objectName(), item.lastModified().toInstant(), item.size()));
            }
            return objects;
        } catch (Exception e) {
            throw new BackupException("cannot list objects under " + prefix, e);
        }
    }

    /** A time-limited link the browser downloads {@code key} from, saved as {@code fileName}. */
    public String downloadUrl(String key, String fileName) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(key)
                    .expiry(properties.getDownloadUrlExpirySeconds())
                    .extraQueryParams(Map.of("response-content-disposition",
                            "attachment; filename=\"" + fileName + "\""))
                    .build());
        } catch (Exception e) {
            throw new BackupException("cannot sign a download link for " + key, e);
        }
    }

    /** Downloads every object under {@code prefix} into {@code dir}, keeping the relative paths. */
    public void downloadPrefix(String prefix, Path dir) {
        for (StoredObject object : list(prefix)) {
            Path target = dir.resolve(object.key().substring(prefix.length()));
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new BackupException("cannot create " + target.getParent(), e);
            }
            download(object.key(), target);
        }
    }

    public long size(String key) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket()).object(key).build()).size();
        } catch (Exception e) {
            throw new BackupException("cannot read the size of " + key, e);
        }
    }

    /** The caller closes the stream. */
    public InputStream open(String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket()).object(key).build());
        } catch (Exception e) {
            throw new BackupException("cannot read " + key, e);
        }
    }

    /** Deletes every object whose key starts with {@code prefix}. */
    public void deletePrefix(String prefix) {
        deleteMatching(prefix, null);
    }

    /** Deletes objects under {@code prefix} that were uploaded before {@code cutoff}. */
    public void deleteOlderThan(String prefix, Instant cutoff) {
        deleteMatching(prefix, cutoff);
    }

    private void deleteMatching(String prefix, Instant cutoff) {
        ensureBucket();
        String bucket = properties.getBucket();
        try {
            List<DeleteRequest.Object> doomed = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                Item item = result.get();
                if (cutoff == null || item.lastModified().toInstant().isBefore(cutoff)) {
                    doomed.add(new DeleteRequest.Object(item.objectName()));
                }
            }
            if (doomed.isEmpty()) {
                return;
            }
            // removeObjects is lazy: the deletes only happen while its results are iterated.
            for (Result<DeleteResult.Error> error : minioClient.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(bucket).objects(doomed).build())) {
                throw new BackupException("cannot delete under " + prefix + ": " + error.get());
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException("cannot delete objects under " + prefix, e);
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        String bucket = properties.getBucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketReady = true;
        } catch (Exception e) {
            throw new BackupException("cannot create bucket " + bucket, e);
        }
    }
}
