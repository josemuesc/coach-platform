package com.coachplatform.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountAuditRepository extends JpaRepository<AccountAudit, UUID> {
}
