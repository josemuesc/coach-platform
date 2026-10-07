package testfixtures.h2;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestNoteRepository extends JpaRepository<TestNote, UUID> {
}
