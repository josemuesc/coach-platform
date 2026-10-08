package com.coachplatform.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.students.api.ConsentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConsentTextRendererTest {

    private static final Pattern ONLY_INERT = Pattern.compile("^(?:[^\\p{Punct}]|\\\\\\p{Punct}|[^\\x00-\\x7F])*$");

    /** Renders "A{{V}}B" and returns what sits between A and B. */
    private static String inserted(String value) {
        String out = ConsentTextRenderer.render("A{{V}}B", Map.of("V", value));
        assertThat(out).startsWith("A").endsWith("B");
        return out.substring(1, out.length() - 1);
    }

    /** What a CommonMark renderer shows for backslash-escaped punctuation. */
    private static String shown(String escaped) {
        return escaped.replaceAll("\\\\(\\p{Punct})", "$1");
    }

    @Test
    void anOrdinaryNameIsInsertedAsIs() {
        assertThat(inserted("María Ñandú Pérez")).isEqualTo("María Ñandú Pérez");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*Ana* _Pérez_ **negrita**",
            "[haz clic](http://evil.example/steal)",
            "![img](http://evil.example/x.png)",
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "Ana <b>Pérez</b> &amp; &lt;",
            "http://evil.example y www.evil.example y a@evil.example",
            "`code` ~~tachado~~ > cita | tabla | # titulo",
            "O'Brien \"el grande\" \\ barra",
            "- item\n- otro",
            "Ana\n\n# Hacked\n\n[x](javascript:alert(1))",
            "Ana\r\n===\r\nPérez",
            "{{CELULAR}} y {{ y }} y {{{{",
    })
    void hostileValuesBecomeInertText(String hostile) {
        String piece = inserted(hostile);
        assertThat(piece).matches(ONLY_INERT);                // every punctuation mark is escaped
        assertThat(piece).doesNotContain("\n").doesNotContain("\r");
        assertThat(piece).doesNotContain("{{");
        assertThat(shown(piece)).isEqualTo(hostile.replaceAll("[\\p{Cntrl}\\s]+", " ").strip());   // the reader sees the same text
    }

    @Test
    void noHtmlTagOrLinkSurvivesUnescaped() {
        String piece = inserted("<a href=\"http://evil.example\">[click](http://evil.example)</a>");
        assertThat(piece).doesNotContainPattern("(?<!\\\\)<").doesNotContainPattern("(?<!\\\\)\\[")
                .doesNotContainPattern("(?<!\\\\)\\]\\(");
    }

    @Test
    void aSmuggledSlotCannotSurviveRendering() {
        String out = ConsentTextRenderer.render("Hola {{NOMBRE}}, tel {{TEL}}", Map.of("NOMBRE", "{{TEL}}", "TEL", "{{NOMBRE}}"));
        assertThat(out).doesNotContain("{{");
    }

    @Test
    void renderingFailsInsteadOfLeavingAnUnfilledSlot() {
        assertThatThrownBy(() -> ConsentTextRenderer.render("Hola {{NOMBRE}}", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentTextRenderer.render("Hola {{NOMBRE}}", Map.of("OTRO", "x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStrayDoubleBraceInTheTemplateItselfIsRefused() {
        assertThatThrownBy(() -> ConsentTextRenderer.render("Hola {{ mal", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameSlotMayAppearTwice() {
        assertThat(ConsentTextRenderer.render("{{V}} y {{V}}", Map.of("V", "Ana"))).isEqualTo("Ana y Ana");
    }

    @Test
    void valuesAreLengthCappedAndBlankOnesStayBlank() {
        assertThat(inserted("x".repeat(1000))).hasSize(ConsentTextRenderer.MAX_VALUE_LENGTH);
        assertThat(inserted("   ")).isEmpty();
    }

    @Test
    void dollarSignsAndBackslashesDoNotBreakTheReplacement() {
        assertThat(shown(inserted("$1 \\ $&"))).isEqualTo("$1 \\ $&");
    }

    // ---- the real texts -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DATA_ADULT", "DATA_GUARDIAN", "WHATSAPP"})
    void theRealTextsParseUseOnlyTheirKnownVariablesAndLeaveNoSlotAfterRendering(String name) throws IOException {
        ConsentType type = ConsentType.valueOf(name);
        ConsentDocument doc = ConsentDocument.parse(Files.readString(Path.of("..", "docs", "consent", name + ".md")));
        assertThat(doc.type()).isEqualTo(type);
        Set<String> used = ConsentTextRenderer.slotsIn(doc.body());
        assertThat(ConsentTextRenderer.variablesFor(type)).containsAll(used);
        Map<String, String> values = Map.of("NOMBRE_DEL_MENOR", "<b>*Pedro* [x](y)</b>", "CELULAR", "+57 300 {{X}}");
        String rendered = ConsentTextRenderer.render(doc.body(), values);
        assertThat(rendered).doesNotContain("{{").doesNotContain("<b>");
    }

    @Test
    void theGuardianAndWhatsappTextsDoUseTheirVariable() throws IOException {
        assertThat(ConsentTextRenderer.slotsIn(ConsentDocument.parse(Files.readString(Path.of("..", "docs", "consent", "DATA_GUARDIAN.md"))).body()))
                .containsExactly("NOMBRE_DEL_MENOR");
        assertThat(ConsentTextRenderer.slotsIn(ConsentDocument.parse(Files.readString(Path.of("..", "docs", "consent", "WHATSAPP.md"))).body()))
                .containsExactly("CELULAR");
    }
}
