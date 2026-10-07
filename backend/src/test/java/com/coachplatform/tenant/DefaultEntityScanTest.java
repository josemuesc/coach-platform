package com.coachplatform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Guards the Postgres ITs (Flyway schema + Hibernate validate): with the default scan, test fixtures must not be
 * registered as entities, otherwise validation fails with "missing table".
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultEntityScanTest {

    @Autowired
    EntityManager em;

    @Test
    void defaultContextRegistersOnlyProductionEntities() {
        Set<String> entities = em.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType).map(Class::getName).collect(Collectors.toSet());

        assertThat(entities).isNotEmpty().allMatch(name -> name.startsWith("com.coachplatform."));
        assertThat(entities).noneMatch(name -> name.contains("TestNote") || name.contains("TenantScopedUser"));
    }
}
