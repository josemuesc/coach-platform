package com.coachplatform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import testfixtures.h2.H2TenantFixtures;
import testfixtures.h2.TestNote;
import testfixtures.h2.TestNoteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = {com.coachplatform.CoachPlatformApplication.class, H2TenantFixtures.class})
@ActiveProfiles("test")
class TenantIsolationTest {

    @Autowired
    TestNoteRepository notes;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void tenantCannotSeeOrLoadAnotherTenantsRows() {
        UUID coachA = UUID.randomUUID();
        UUID coachB = UUID.randomUUID();

        TenantContext.set(coachA);
        UUID noteId = notes.save(new TestNote("secret of A")).getId();
        assertThat(notes.findAll()).hasSize(1);

        TenantContext.set(coachB);
        assertThat(notes.findAll()).isEmpty();
        assertThat(notes.findById(noteId)).isEmpty();

        TenantContext.set(coachA);
        assertThat(notes.findById(noteId)).isPresent();
    }

    @Test
    void noTenantSeesNothing() {
        TenantContext.set(UUID.randomUUID());
        notes.save(new TestNote("x"));

        TenantContext.clear();
        assertThat(notes.findAll()).isEmpty();
    }
}
