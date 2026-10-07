package testfixtures.postgres;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Adds the tenant-scoped app_user mapping to the production scan. Import it only from the tenant Postgres IT. */
@Configuration
@EntityScan({"com.coachplatform", "testfixtures.postgres"})
@EnableJpaRepositories({"com.coachplatform", "testfixtures.postgres"})
public class PostgresTenantFixtures {
}
