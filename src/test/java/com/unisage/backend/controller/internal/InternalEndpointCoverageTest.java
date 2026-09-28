package com.unisage.backend.controller.internal;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the real Spring route mappings — not a source scan — and cross-checks against the contract. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class InternalEndpointCoverageTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void internalProps(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void appIsServlet_noReactiveHandlerMapping() {
        assertThat(context.getBeanNamesForType(org.springframework.web.reactive.HandlerMapping.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.web.servlet.DispatcherServlet.class)).isNotEmpty();
    }

    @Test
    void internalRouteMappings_matchContract() throws IOException {
        Set<String> actual = new HashSet<>();
        for (AbstractHandlerMethodMapping<?> mapping : context.getBeansOfType(AbstractHandlerMethodMapping.class).values()) {
            for (Map.Entry<?, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                for (String route : patternsOf(entry.getKey(), entry.getValue())) {
                    if (route.startsWith("/internal/")) {
                        actual.add(route);
                    }
                }
            }
        }

        // Each endpoint carries its own basePath (Cost Tracking's live under /internal, Model
        // Registry's under /internal/model-registry) - both relative to the servlet mapping
        // pattern, which excludes the /api/v1 context path.
        Set<String> contract = new HashSet<>();
        try (InputStream in = new FileInputStream("contracts/internal-endpoints.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode endpoint : root.get("endpoints")) {
                contract.add(endpoint.get("basePath").asText() + endpoint.get("path").asText());
            }
        }

        // Until every endpoint lands, actual must be a subset of the contract — never more, and
        // never a route the contract doesn't know about.
        assertThat(actual).as("route not declared in contracts/internal-endpoints.json").isSubsetOf(contract);
    }

    @Test
    void noControllerOutsideInternalPackage_returnsInternalDto() {
        for (Object bean : context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class).values()) {
            Class<?> beanClass = bean.getClass();
            if (beanClass.getPackageName().equals(InternalModelRegistryController.class.getPackageName())) {
                continue;
            }
            for (Method method : beanClass.getMethods()) {
                if (!method.isAnnotationPresent(RequestMapping.class) && method.getAnnotations().length == 0) {
                    continue;
                }
                String returnTypePackage = method.getReturnType().getPackageName();
                assertThat(returnTypePackage)
                        .as(beanClass.getSimpleName() + "#" + method.getName())
                        .doesNotContain("dto.response.internal")
                        .doesNotContain("dto.request.internal");
            }
        }
    }

    private java.util.List<String> patternsOf(Object mappingInfo, HandlerMethod handlerMethod) {
        // RequestMappingInfo#getPatternValues() is the stable way to read the path patterns
        // regardless of PathPattern vs AntPathMatcher parsing mode.
        try {
            Method getPatternValues = mappingInfo.getClass().getMethod("getPatternValues");
            @SuppressWarnings("unchecked")
            Set<String> patterns = (Set<String>) getPatternValues.invoke(mappingInfo);
            return new java.util.ArrayList<>(patterns);
        } catch (ReflectiveOperationException e) {
            return java.util.List.of();
        }
    }
}
