package com.coachplatform.coach.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the trainer's brand looks like.
 *
 * @param primaryColor {@code #RRGGBB} (upper case), or null while the coach never chose one: the client then uses its default
 */
public record BrandView(String brandName, @Schema(nullable = true) String primaryColor) {
}
