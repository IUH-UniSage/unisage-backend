package com.unisage.backend.service.modelregistry;

import com.unisage.backend.event.consumer.ModelRegistryEventPublisher;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(ModelRegistryVersionServiceTest.TestTransactionalCaller.class)
class ModelRegistryVersionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
    }

    @Autowired
    private ModelRegistryVersionService versionService;

    @Autowired
    private TestTransactionalCaller testCaller;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisMessageListenerContainer listenerContainer;
    private BlockingQueue<String> received;

    @BeforeEach
    void setUp() {
        received = new ArrayBlockingQueue<>(10);
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(redisTemplate.getConnectionFactory());
        MessageListener listener = (message, pattern) -> received.add(new String(message.getBody()));
        listenerContainer.addMessageListener(listener, new PatternTopic(ModelRegistryEventPublisher.CHANNEL));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
    }

    @AfterEach
    void tearDown() {
        listenerContainer.stop();
    }

    @Test
    void commit_publishesExactlyOnce_withNewVersion() throws InterruptedException {
        long before = versionService.currentVersion();

        long returned = testCaller.bumpAndCommit();

        assertThat(returned).isEqualTo(before + 1);
        String message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).isEqualTo(String.valueOf(before + 1));
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void rollback_neverPublishes() throws InterruptedException {
        long before = versionService.currentVersion();

        try {
            testCaller.bumpAndRollback();
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(versionService.currentVersion()).isEqualTo(before);
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Service
    static class TestTransactionalCaller {

        private final ModelRegistryVersionService versionService;

        TestTransactionalCaller(ModelRegistryVersionService versionService) {
            this.versionService = versionService;
        }

        @Transactional
        public long bumpAndCommit() {
            return versionService.bump();
        }

        @Transactional
        public long bumpAndRollback() {
            long version = versionService.bump();
            throw new RuntimeException("force rollback after version=" + version);
        }
    }
}
