package com.unisage.backend.config;

import java.util.List;

import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.unisage.backend.audit.AuditHibernateIntegrator;

/**
 * Wires {@link AuditHibernateIntegrator} into Hibernate's bootstrap via the
 * {@code hibernate.integrator_provider} setting — the supported Spring Boot way to register a
 * Hibernate {@code Integrator} without an empty-jar {@code META-INF/services} file. See
 * docs/adr/0003-audit-log-persistence.md.
 */
@Configuration
public class AuditConfig {

    @Bean
    public HibernatePropertiesCustomizer auditHibernateIntegratorCustomizer() {
        return properties -> properties.put(
                "hibernate.integrator_provider",
                (IntegratorProvider) () -> List.of(new AuditHibernateIntegrator()));
    }
}
