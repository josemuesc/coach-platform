package com.coachplatform.support;

import com.coachplatform.students.domain.ConsentDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the versions in force straight from docs/consent, so tests never hardcode a text version. */
public final class ConsentFixtures {

    private ConsentFixtures() {
    }

    public static String version(String type) {
        try {
            return ConsentDocument.parse(Files.readString(Path.of("..", "docs", "consent", type + ".md"))).version();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JSON of a valid acceptance: the data authorization (adult or guardian) and, optionally, WhatsApp. */
    public static String acceptJson(String token, String password, boolean guardian, boolean whatsapp) {
        String data = version(guardian ? "DATA_GUARDIAN" : "DATA_ADULT");
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"acceptData\":true,\"dataVersion\":\"" + data
                + "\",\"acceptWhatsapp\":" + whatsapp + ",\"whatsappVersion\":" + (whatsapp ? "\"" + version("WHATSAPP") + "\"" : "null") + "}";
    }

    public static String acceptJson(String token, String password) {
        return acceptJson(token, password, false, false);
    }
}
