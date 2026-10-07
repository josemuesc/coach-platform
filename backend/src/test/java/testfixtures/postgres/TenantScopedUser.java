package testfixtures.postgres;

import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Test-only second mapping of the REAL app_user table (created by Flyway V1), this time tenant-scoped,
 * to prove Hibernate's tenant filter and the real FK/constraints work together on PostgreSQL.
 * Production code maps app_user without tenant filtering on purpose (login looks up by email).
 */
@Entity
@Table(name = "app_user")
public class TenantScopedUser extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash = "x";

    private String role = "STUDENT";

    protected TenantScopedUser() {
    }

    public TenantScopedUser(String email) {
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
}
