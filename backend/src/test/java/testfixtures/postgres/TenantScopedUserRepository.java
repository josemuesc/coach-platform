package testfixtures.postgres;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantScopedUserRepository extends JpaRepository<TenantScopedUser, UUID> {
}
