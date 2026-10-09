package com.coachplatform.students.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What the person opening an invitation sees: the brand, who it is for, who must authorize, and the texts to accept.
 *
 * @param primaryColor {@code #RRGGBB} or null (the client uses its default)
 * @param guardianName only for a minor; null for an adult
 */
public record InvitationPreview(String brandName, @Schema(nullable = true) String primaryColor, String studentName, Audience audience,
                                @Schema(nullable = true) String guardianName, List<ConsentTextView> consents) {
}
