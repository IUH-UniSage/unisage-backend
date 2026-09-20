package com.unisage.backend.audit;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Static bridge to the Spring {@link ApplicationContext}, needed because Hibernate
 * {@code PostInsertEventListener}/{@code PostUpdateEventListener}/{@code PostDeleteEventListener}
 * instances are instantiated directly by Hibernate's {@code Integrator} SPI (see
 * {@link AuditHibernateIntegrator}), not by Spring — so they cannot get beans injected via
 * constructor/field autowiring. See docs/adr/0003-audit-log-persistence.md, section on this
 * known DI gotcha.
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> type) {
        ApplicationContext ctx = context;
        return ctx != null ? ctx.getBean(type) : null;
    }
}
