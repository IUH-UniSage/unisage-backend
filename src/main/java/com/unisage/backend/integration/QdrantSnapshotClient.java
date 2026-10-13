package com.unisage.backend.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.service.backup.BackupException;
import com.unisage.backend.service.backup.BackupProperties;

/**
 * Qdrant collection snapshot REST API (ADR-0009). A snapshot is created on the Qdrant node, so
 * it is downloaded and deleted there once it is safely in object storage.
 */
@Component
public class QdrantSnapshotClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final BackupProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    // RestTemplate buffers a multipart body in memory; a snapshot is close to a gigabyte.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    public QdrantSnapshotClient(BackupProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        // Creating a snapshot of a large collection (wait=true) and downloading it both take minutes.
        factory.setReadTimeout((int) Math.min(Integer.MAX_VALUE, properties.getQdrantTimeoutMinutes() * 60_000));
        this.restTemplate = new RestTemplate(factory);
    }

    public record Snapshot(String name, long size, String checksum) {
    }

    @FunctionalInterface
    public interface BodyHandler<T> {
        T handle(InputStream body) throws Exception;
    }

    public List<String> listCollections() {
        JsonNode collections = call(HttpMethod.GET, uri("/collections"), "list collections")
                .path("result").path("collections");
        List<String> names = new ArrayList<>();
        collections.forEach(collection -> names.add(collection.path("name").asText()));
        return names;
    }

    public Snapshot createSnapshot(String collection) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getQdrantUrl())
                .path("/collections/{collection}/snapshots")
                .queryParam("wait", true)
                .buildAndExpand(collection).encode().toUri();
        JsonNode result = call(HttpMethod.POST, uri, "snapshot " + collection).path("result");
        String name = result.path("name").asText(null);
        if (name == null) {
            throw new BackupException("Qdrant returned no snapshot name for " + collection);
        }
        return new Snapshot(name, result.path("size").asLong(-1), result.path("checksum").asText(null));
    }

    public <T> T download(String collection, String snapshot, BodyHandler<T> handler) {
        URI uri = snapshotUri(collection, snapshot);
        try {
            return restTemplate.execute(uri, HttpMethod.GET,
                    request -> request.getHeaders().addAll(headers()),
                    response -> {
                        try {
                            return handler.handle(response.getBody());
                        } catch (BackupException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new BackupException("cannot store snapshot " + snapshot, e);
                        }
                    });
        } catch (RestClientException e) {
            throw new BackupException("cannot download Qdrant snapshot " + snapshot, e);
        }
    }

    public void deleteSnapshot(String collection, String snapshot) {
        URI uri = UriComponentsBuilder.fromUri(snapshotUri(collection, snapshot)).queryParam("wait", true).build().toUri();
        call(HttpMethod.DELETE, uri, "delete snapshot " + snapshot);
    }

    /**
     * Recovers {@code collection} from a snapshot streamed from {@code body}, replacing what the
     * collection holds now ({@code priority=snapshot}); creates the collection if it is gone.
     */
    public void uploadSnapshot(String collection, String fileName, InputStream body, long size) {
        String boundary = "unisage-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"snapshot\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        URI uri = UriComponentsBuilder.fromUriString(properties.getQdrantUrl())
                .path("/collections/{collection}/snapshots/upload")
                .queryParam("priority", "snapshot")
                .queryParam("wait", true)
                .buildAndExpand(collection).encode().toUri();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(properties.getQdrantTimeoutMinutes()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.fromPublisher(BodyPublishers.concat(
                        BodyPublishers.ofByteArray(head),
                        BodyPublishers.ofInputStream(() -> body),
                        BodyPublishers.ofByteArray(tail)), head.length + size + tail.length));
        headers().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new BackupException("Qdrant could not recover " + collection + " from " + fileName
                        + ": HTTP " + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new BackupException("cannot upload snapshot " + fileName + " to Qdrant", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrupted while uploading snapshot " + fileName, e);
        }
    }

    private JsonNode call(HttpMethod method, URI uri, String action) {
        try {
            String body = restTemplate.exchange(uri, method, new HttpEntity<>(headers()), String.class).getBody();
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientException e) {
            throw new BackupException("Qdrant could not " + action, e);
        } catch (Exception e) {
            throw new BackupException("Qdrant sent an unreadable reply to " + action, e);
        }
    }

    private URI uri(String path) {
        return UriComponentsBuilder.fromUriString(properties.getQdrantUrl()).path(path).build().toUri();
    }

    private URI snapshotUri(String collection, String snapshot) {
        return UriComponentsBuilder.fromUriString(properties.getQdrantUrl())
                .path("/collections/{collection}/snapshots/{snapshot}")
                .buildAndExpand(collection, snapshot).encode().toUri();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        String apiKey = properties.getQdrantApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        return headers;
    }
}
