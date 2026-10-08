package com.coachplatform.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.ConsentDocument.Status;
import org.junit.jupiter.api.Test;

class ConsentDocumentTest {

    private static String file(String type, String version, String status, String title, String body) {
        return "---\ntype: " + type + "\nversion: " + version + "\nstatus: " + status + "\ntitle: " + title + "\n---\n" + body + "\n";
    }

    private static final String CLEAN = file("DATA_ADULT", "2026-11-v1", "FINAL", "Autorización de datos", "Autorizo el tratamiento de mis datos.");

    @Test
    void parsesTheHeaderAndTheBody() {
        ConsentDocument doc = ConsentDocument.parse(CLEAN);
        assertThat(doc.type()).isEqualTo(ConsentType.DATA_ADULT);
        assertThat(doc.version()).isEqualTo("2026-11-v1");
        assertThat(doc.status()).isEqualTo(Status.FINAL);
        assertThat(doc.title()).isEqualTo("Autorización de datos");
        assertThat(doc.body()).isEqualTo("Autorizo el tratamiento de mis datos.");
    }

    @Test
    void theHashIsOfTheExactTextAndKnownValue() {
        assertThat(ConsentDocument.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ConsentDocument.parse(CLEAN).textSha256()).isEqualTo(ConsentDocument.sha256Hex(CLEAN));
    }

    @Test
    void anyChangeToTheTextChangesTheHash() {
        String edited = CLEAN.replace("mis datos", "mis datos personales");
        assertThat(ConsentDocument.parse(edited).textSha256()).isNotEqualTo(ConsentDocument.parse(CLEAN).textSha256());
    }

    @Test
    void lineEndingsDoNotChangeTheHash() {
        assertThat(ConsentDocument.parse(CLEAN.replace("\n", "\r\n")).textSha256()).isEqualTo(ConsentDocument.parse(CLEAN).textSha256());
    }

    @Test
    void aMalformedFileIsRefused() {
        assertThatThrownBy(() -> ConsentDocument.parse("no header")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentDocument.parse("---\ntype: DATA_ADULT\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentDocument.parse("---\ntype: DATA_ADULT\nversion: v1\n---\nbody\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentDocument.parse(file("DATA_ADULT", "v1", "FINAL", "t", ""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentDocument.parse(file("NOPE", "v1", "FINAL", "t", "b"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- production gate ------------------------------------------------------------------------------

    @Test
    void aFinalCleanTextMayBeUsedInProduction() {
        assertThat(ConsentDocument.parse(CLEAN).productionProblems()).isEmpty();
    }

    @Test
    void aTextMarkedAsDraftIsNotAllowed() {
        assertThat(ConsentDocument.parse(file("DATA_ADULT", "2026-11-v1", "DRAFT", "t", "texto")).productionProblems())
                .anyMatch(p -> p.contains("draft"));
    }

    @Test
    void aDraftVersionIsNotAllowedEvenIfMarkedFinal() {
        assertThat(ConsentDocument.parse(file("WHATSAPP", "borrador-1", "FINAL", "t", "texto")).productionProblems())
                .anyMatch(p -> p.contains("borrador-1"));
        assertThat(ConsentDocument.parse(file("WHATSAPP", "Borrador-2", "FINAL", "t", "texto")).productionProblems()).isNotEmpty();
    }

    @Test
    void anyOpenBracketInTheBodyOrTitleIsAnUnfilledPlaceholder() {
        assertThat(ConsentDocument.parse(file("DATA_ADULT", "v1", "FINAL", "t", "Responsable: [NOMBRE]")).productionProblems())
                .anyMatch(p -> p.contains("placeholder"));
        assertThat(ConsentDocument.parse(file("DATA_ADULT", "v1", "FINAL", "Título [X]", "texto")).productionProblems())
                .anyMatch(p -> p.contains("placeholder"));
        assertThat(ConsentDocument.parse(file("DATA_ADULT", "v1", "FINAL", "t", "plazo [10 días hábiles] (verificar)")).productionProblems())
                .isNotEmpty();
    }

    @Test
    void allTheProblemsAreReportedTogether() {
        assertThat(ConsentDocument.parse(file("DATA_GUARDIAN", "borrador-1", "DRAFT", "t", "[X]")).productionProblems()).hasSize(3);
    }
}
