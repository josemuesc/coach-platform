package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the {@code {{VARIABLE}}} slots of a consent text with real values (a minor's name, a phone number). The values are
 * untrusted (a coach typed them), so each one is made inert before it enters the Markdown: every ASCII punctuation character
 * is backslash-escaped (CommonMark renders {@code \<} as a literal {@code <}), which kills HTML, links, emphasis, headings and
 * a smuggled {@code {{...}}}; newlines and control characters become spaces so a value can never open a new block.
 * Rendering fails rather than leaving a slot unfilled, and the result never contains {@code {{}.
 *
 * <p>The frontend must still render this Markdown without raw HTML.
 */
public final class ConsentTextRenderer {

    public static final int MAX_VALUE_LENGTH = 200;
    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)}}");

    /** The variables each text may use. A typo in a text file is caught by a test against this list. */
    public static Set<String> variablesFor(ConsentType type) {
        return switch (type) {
            case DATA_ADULT -> Set.of();
            case DATA_GUARDIAN -> Set.of("NOMBRE_DEL_MENOR");
            case WHATSAPP -> Set.of("CELULAR");
        };
    }

    /** The variables the template actually uses. */
    public static Set<String> slotsIn(String template) {
        Set<String> slots = new LinkedHashSet<>();
        Matcher m = SLOT.matcher(template);
        while (m.find()) {
            slots.add(m.group(1));
        }
        return slots;
    }

    public static String render(String template, Map<String, String> values) {
        Matcher m = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("No value for the consent text variable " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(inert(value)));
        }
        m.appendTail(out);
        String rendered = out.toString();
        if (rendered.contains("{{")) {
            throw new IllegalArgumentException("The consent text still has an unfilled {{ }} slot");
        }
        return rendered;
    }

    /** Single-line, length-capped and with every ASCII punctuation character escaped. */
    static String inert(String value) {
        StringBuilder flat = new StringBuilder();
        value.codePoints().forEach(cp -> flat.appendCodePoint(Character.isISOControl(cp) || Character.isWhitespace(cp) ? ' ' : cp));
        String clean = flat.toString().strip().replaceAll(" {2,}", " ");
        if (clean.length() > MAX_VALUE_LENGTH) {
            clean = clean.substring(0, MAX_VALUE_LENGTH);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c < 128 && !Character.isLetterOrDigit(c) && c != ' ') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
