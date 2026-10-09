package com.coachplatform.students.api;

/**
 * An authorization text in force, with its variables already filled (inert Markdown: render it without raw HTML). {@code draft}: the
 * text is not ready for real students (draft status, draft version or an unfilled [placeholder]); the production profile refuses to
 * start with such a text, so this is only ever true in development and tests.
 */
public record ConsentTextView(ConsentType type, String version, String title, String bodyMarkdown, boolean required, boolean draft) {
}
