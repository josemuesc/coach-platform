package com.coachplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/error-codes.md is the contract with the frontend: every error code the server can send is in it (and with the right HTTP
 * status when the code states it), and it lists no code the server cannot send. Codes are read from the source, so a new
 * {@code enum Code} constant, {@code ApiException} or handler code fails this test until the document is updated.
 */
class ErrorCodesDocTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path DOC = Path.of("..", "docs", "error-codes.md");

    private static final Map<String, Integer> STATUS = Map.of("BAD_REQUEST", 400, "UNAUTHORIZED", 401, "FORBIDDEN", 403,
            "NOT_FOUND", 404, "CONFLICT", 409, "UNPROCESSABLE_ENTITY", 422, "TOO_MANY_REQUESTS", 429);

    /** code -> HTTP status in the document */
    private static Map<String, Integer> documented() throws IOException {
        Map<String, Integer> codes = new TreeMap<>();
        Pattern row = Pattern.compile("^\\|\\s*`([A-Z][A-Z0-9_]+)`\\s*\\|\\s*(\\d{3})\\s*\\|");
        for (String line : Files.readAllLines(DOC)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                assertThat(codes.put(m.group(1), Integer.parseInt(m.group(2)))).as("code listed twice: " + m.group(1)).isNull();
            }
        }
        return codes;
    }

    private record InCode(TreeSet<String> all, Map<String, Integer> statusKnown) {
    }

    private static InCode inCode() throws IOException {
        TreeSet<String> all = new TreeSet<>();
        Map<String, Integer> statusKnown = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String src = Files.readString(file);
            Matcher enums = Pattern.compile("enum Code\\s*\\{([^}]*)\\}").matcher(src);
            while (enums.find()) {
                for (String c : enums.group(1).split(",")) {
                    if (!c.isBlank()) {
                        all.add(c.strip());
                    }
                }
            }
            Matcher api = Pattern.compile("HttpStatus\\.([A-Z_]+)\\s*,\\s*\"([A-Z][A-Z0-9_]+)\"").matcher(src);
            while (api.find()) {
                all.add(api.group(2));
                Integer status = STATUS.get(api.group(1));
                assertThat(status).as("add " + api.group(1) + " to STATUS in this test").isNotNull();
                statusKnown.put(api.group(2), status);
            }
            if (file.getFileName().toString().endsWith("ExceptionHandler.java")) {
                // only the handlers' own bodies; "code" keys nested in details (e.g. the renewal options) are not error codes
                Matcher body = Pattern.compile("status\\(HttpStatus\\.([A-Z_]+)\\)\\.body\\(Map\\.of\\(\"code\",\\s*\"([A-Z][A-Z0-9_]+)\"").matcher(src);
                while (body.find()) {
                    all.add(body.group(2));
                    statusKnown.put(body.group(2), STATUS.get(body.group(1)));
                }
            }
        }
        return new InCode(all, statusKnown);
    }

    @Test
    void everyCodeInTheSourceIsDocumented() throws IOException {
        List<String> missing = new ArrayList<>(inCode().all());
        missing.removeAll(documented().keySet());
        assertThat(missing).as("codes the server can send that docs/error-codes.md does not list").isEmpty();
    }

    @Test
    void theDocumentListsNoCodeThatTheServerCannotSend() throws IOException {
        List<String> extra = new ArrayList<>(documented().keySet());
        extra.removeAll(inCode().all());
        assertThat(extra).as("codes in docs/error-codes.md that no source produces").isEmpty();
    }

    @Test
    void theStatusInTheDocumentMatchesTheOneTheSourceStates() throws IOException {
        Map<String, Integer> docs = documented();
        inCode().statusKnown().forEach((code, status) -> {
            if (docs.containsKey(code) && status != null) {
                assertThat(docs.get(code)).as("HTTP status of " + code).isEqualTo(status);
            }
        });
    }
}
