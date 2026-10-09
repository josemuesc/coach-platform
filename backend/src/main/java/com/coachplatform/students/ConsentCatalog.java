package com.coachplatform.students;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.api.ConsentTextView;
import com.coachplatform.students.domain.ConsentDocument;
import com.coachplatform.students.domain.ConsentOffer;
import com.coachplatform.students.domain.ConsentRules;
import com.coachplatform.students.domain.ConsentTextRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The authorization texts in force (docs/consent, packaged as classpath:consent/). With the "prod" profile (or
 * app.consent.require-final=true) the application REFUSES TO START if any text is still a draft, has a draft version or has
 * an unfilled [placeholder]. Elsewhere drafts are fine for development and tests.
 */
@Component
class ConsentCatalog {

    private final Map<ConsentType, ConsentDocument> documents = new EnumMap<>(ConsentType.class);

    ConsentCatalog(ResourceLoader loader, Environment env, @Value("${app.consent.require-final:false}") boolean requireFinal) {
        for (ConsentType type : ConsentType.values()) {
            ConsentDocument doc = ConsentDocument.parse(read(loader.getResource("classpath:consent/" + type + ".md")));
            if (doc.type() != type) {
                throw new IllegalStateException("consent/" + type + ".md declares type " + doc.type());
            }
            documents.put(type, doc);
        }
        if (requireFinal || env.acceptsProfiles(Profiles.of("prod"))) {
            List<String> problems = new ArrayList<>();
            documents.values().forEach(d -> problems.addAll(d.productionProblems()));
            if (!problems.isEmpty()) {
                throw new IllegalStateException("The consent texts are not ready for production: " + String.join("; ", problems));
            }
        }
    }

    ConsentOffer offer(ConsentType type) {
        ConsentDocument d = documents.get(type);
        return new ConsentOffer(type, d.version(), d.textSha256());
    }

    Map<ConsentType, ConsentOffer> offers() {
        Map<ConsentType, ConsentOffer> offers = new EnumMap<>(ConsentType.class);
        documents.keySet().forEach(t -> offers.put(t, offer(t)));
        return offers;
    }

    /** The text in force with its variables filled; every value is made inert before it enters the Markdown. */
    ConsentTextView view(ConsentType type, Map<String, String> values) {
        ConsentDocument d = documents.get(type);
        return new ConsentTextView(type, d.version(), d.title(), ConsentTextRenderer.render(d.body(), values), ConsentRules.isRequired(type));
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing consent text " + resource, e);
        }
    }
}
