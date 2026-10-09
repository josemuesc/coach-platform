package com.coachplatform.students.api;

import java.util.List;

/** What the person opening an invitation sees: the brand, who it is for, who must authorize, and the texts to accept. */
public record InvitationPreview(String brandName, String studentName, Audience audience, String guardianName,
                                List<ConsentTextView> consents) {
}
