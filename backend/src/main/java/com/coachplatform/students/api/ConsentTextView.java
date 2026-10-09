package com.coachplatform.students.api;

/** An authorization text in force, with its variables already filled (inert Markdown: render it without raw HTML). */
public record ConsentTextView(ConsentType type, String version, String title, String bodyMarkdown, boolean required) {
}
