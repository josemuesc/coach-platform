package com.coachplatform.students.domain;

import java.util.Locale;

/** The legal guardian of a student: all four fields travel together. The email is the guardian's access login. */
public record GuardianData(String name, String relationship, String phone, String email) {

    public static final GuardianData NONE = new GuardianData(null, null, null, null);

    public GuardianData {
        name = trimToNull(name);
        relationship = trimToNull(relationship);
        phone = trimToNull(phone);
        email = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isComplete() {
        return name != null && relationship != null && phone != null && email != null;
    }

    public boolean isEmpty() {
        return name == null && relationship == null && phone == null && email == null;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
