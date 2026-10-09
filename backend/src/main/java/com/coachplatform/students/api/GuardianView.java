package com.coachplatform.students.api;

/** The legal guardian on file. The email is only the guardian's access login. */
public record GuardianView(String name, String relationship, String phone, String email) {
}
