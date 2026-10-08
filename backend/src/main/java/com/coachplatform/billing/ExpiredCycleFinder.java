package com.coachplatform.billing;

import com.coachplatform.tenant.CrossTenantAccess;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * THE single place where billing reads across tenants: it lists the active cycles whose deadline passed, for every
 * coach. It is package-private, exposed by no endpoint, and returns ids only; the job then switches to each coach.
 */
@Component
@CrossTenantAccess
class ExpiredCycleFinder {

    record ExpiredCycle(UUID cycleId, UUID coachId) {
    }

    private final JdbcClient jdbc;

    ExpiredCycleFinder(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    List<ExpiredCycle> findOverdue(LocalDate today) {
        return jdbc.sql("select id, coach_id from cycle where status = 'ACTIVE' and end_date < :today")
                .param("today", today)
                .query((rs, n) -> new ExpiredCycle(rs.getObject("id", UUID.class), rs.getObject("coach_id", UUID.class)))
                .list();
    }
}
