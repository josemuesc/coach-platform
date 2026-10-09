package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.students.api.ConsentType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/** The startup gate: with the prod profile the application refuses to start while any text is still a draft. */
class ConsentCatalogTest {

    private final DefaultResourceLoader loader = new DefaultResourceLoader();

    @Test
    void developmentAndTestsMayRunWithTheDraftTexts() {
        assertThatCode(() -> new ConsentCatalog(loader, new MockEnvironment(), false)).doesNotThrowAnyException();
    }

    @Test
    void theProdProfileRefusesToStartWhileTheTextsAreDrafts() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ConsentCatalog(loader, prod, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready for production").hasMessageContaining("draft").hasMessageContaining("placeholder");
    }

    @Test
    void theExplicitSettingAlsoRefusesDrafts() {
        assertThatThrownBy(() -> new ConsentCatalog(loader, new MockEnvironment(), true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyTypeIsLoadedWithItsVersionAndHash() {
        ConsentCatalog catalog = new ConsentCatalog(loader, new MockEnvironment(), false);
        for (ConsentType type : ConsentType.values()) {
            assertThat(catalog.offer(type).version()).isNotBlank();
            assertThat(catalog.offer(type).textSha256()).matches("[0-9a-f]{64}");
        }
        assertThat(catalog.offers()).hasSize(3);
    }
}
