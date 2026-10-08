package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A versioned authorization text ({@code docs/consent/<TYPE>.md}). Format: a header between two {@code ---} lines with
 * {@code type}, {@code version}, {@code status} (DRAFT | FINAL) and {@code title}, then the Markdown body. The recorded hash
 * is the SHA-256 of the whole file (line endings normalized to LF), so it proves the exact text that was accepted.
 */
public record ConsentDocument(ConsentType type, String version, Status status, String title, String body, String textSha256) {

    public enum Status { DRAFT, FINAL }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[");

    public static ConsentDocument parse(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("The consent text must start with a --- header");
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("The consent text header is not closed with ---");
        }
        String type = null, version = null, status = null, title = null;
        for (String line : text.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "type" -> type = value;
                case "version" -> version = value;
                case "status" -> status = value;
                case "title" -> title = value;
                default -> { }
            }
        }
        if (type == null || version == null || version.isBlank() || status == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("The consent header needs type, version, status and title");
        }
        String body = text.substring(end + 5).strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("The consent text has no body");
        }
        return new ConsentDocument(ConsentType.valueOf(type.toUpperCase(Locale.ROOT)), version,
                Status.valueOf(status.toUpperCase(Locale.ROOT)), title, body, sha256Hex(text));
    }

    /** Why this text may NOT be used with real students (empty when it may). Checked at production startup. */
    public List<String> productionProblems() {
        List<String> problems = new java.util.ArrayList<>();
        if (status != Status.FINAL) {
            problems.add(type + " is marked as a draft");
        }
        if (version.toLowerCase(Locale.ROOT).startsWith("borrador")) {
            problems.add(type + " has a draft version (" + version + ")");
        }
        if (PLACEHOLDER.matcher(title).find() || PLACEHOLDER.matcher(body).find()) {
            problems.add(type + " still has an unfilled [placeholder]");
        }
        return problems;
    }

    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
