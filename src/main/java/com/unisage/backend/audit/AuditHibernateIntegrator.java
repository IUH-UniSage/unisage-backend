package com.unisage.backend.audit;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Registers {@link AuditEventListener} against Hibernate's POST_INSERT/POST_UPDATE/POST_DELETE
 * event types for every entity in the session factory — the mechanism that gives us "every DB
 * write is audited" without touching each *ServiceImpl*. Wired in via
 * {@code hibernate.integrator_provider} (see {@code config/AuditConfig.java}), since Hibernate
 * instantiates {@code Integrator}s itself, outside of Spring's bean lifecycle.
 */
public class AuditHibernateIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
                           SessionFactoryServiceRegistry serviceRegistry) {
        EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
        AuditEventListener listener = new AuditEventListener();
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                              SessionFactoryServiceRegistry serviceRegistry) {
        // Nothing to clean up.
    }
}
