package com.coachplatform.students.api;

/** Alert shown to the coach about the age of a student. */
public enum AgeAlert {
    NONE,
    /** A minor who turns 18 in 60 days or fewer. */
    TURNS_ADULT_SOON,
    /** Already 18 but the guardian still holds the account: a new authorization from the student is due. */
    TURNED_ADULT_NEEDS_AUTHORIZATION
}
