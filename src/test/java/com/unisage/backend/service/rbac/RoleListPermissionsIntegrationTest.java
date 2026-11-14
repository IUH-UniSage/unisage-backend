package com.unisage.backend.service.rbac;

import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import com.unisage.backend.dto.response.RoleResponse;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** The batched role list must return the same permissions as the per-role detail query. */
class RoleListPermissionsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private RbacService rbacService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void roleListPermissionsMatchRoleDetails() {
        // Outside any session: the batched query must already have fetched every permission.
        var roles = rbacService.getAllRoles(PageRequest.of(0, 50)).data();

        assertThat(roles).isNotEmpty();
        assertThat(roles).anySatisfy(role -> assertThat(role.permissions()).isNotEmpty());
        for (RoleResponse role : roles) {
            // The per-role detail path reads permissions lazily, so it needs a session.
            var expected = transactionTemplate.execute(status -> rbacService.getRoleDetails(role.id()).permissions());
            assertThat(role.permissions())
                    .usingElementComparator(Comparator.comparing(p -> p.id()))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }
}
