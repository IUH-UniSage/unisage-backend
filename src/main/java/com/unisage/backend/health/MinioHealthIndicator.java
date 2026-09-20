package com.unisage.backend.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-62: MinIO reachability probe, reusing the already-injected {@code minioClient} bean
 * (see {@code MinioConfig}/{@code FileServiceImpl}) rather than opening a separate connection.
 * {@code bucketExists} both proves connectivity and confirms the configured bucket is actually
 * there.
 */
@Component
@RequiredArgsConstructor
public class MinioHealthIndicator implements HealthIndicator {

    @Qualifier("minioClient")
    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            long elapsedMs = System.currentTimeMillis() - start;
            if (exists) {
                return Health.up()
                        .withDetail("bucket", bucket)
                        .withDetail("responseTimeMs", elapsedMs)
                        .build();
            }
            return Health.down()
                    .withDetail("bucket", bucket)
                    .withDetail("reason", "bucket does not exist")
                    .withDetail("responseTimeMs", elapsedMs)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("bucket", bucket)
                    .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                    .build();
        }
    }
}
