package com.unisage.backend.integration;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
