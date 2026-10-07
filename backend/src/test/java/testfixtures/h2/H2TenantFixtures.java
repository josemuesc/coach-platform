package testfixtures.h2;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Adds the H2-only fixtures to the production scan. Import it only from H2 tests. */
@Configuration
@EntityScan({"com.coachplatform", "testfixtures.h2"})
@EnableJpaRepositories({"com.coachplatform", "testfixtures.h2"})
public class H2TenantFixtures {
}
