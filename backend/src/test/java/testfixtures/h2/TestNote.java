package testfixtures.h2;

import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.UUID;

/**
 * Test-only tenant-scoped entity whose table exists only in H2 (create-drop). It lives OUTSIDE com.coachplatform
 * so the default entity scan never picks it up in contexts that validate the real Flyway schema.
 */
@Entity
public class TestNote extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String text;

    protected TestNote() {
    }

    public TestNote(String text) {
        this.text = text;
    }

    public UUID getId() { return id; }
}
