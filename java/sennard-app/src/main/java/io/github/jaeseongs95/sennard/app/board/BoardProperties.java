// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.board;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The values the board uses ({@code sennard.board.*}). The two lengths are also in the published tool contracts (contracts/update-session-status-request.v1.schema.json:
 * 200 and 120), so they can be lowered but not raised: a longer line would pass this check and fail the contract. BoardPropertiesTest holds the defaults to the contract.
 */
@ConfigurationProperties("sennard.board")
@Validated
public record BoardProperties(
        @DefaultValue("200") @Min(1) @Max(200) int summaryMaxLength,
        @DefaultValue("120") @Min(1) @Max(120) int roleMaxLength,
        // a row not seen for this long is removed
        @DefaultValue("7d") @NotNull Duration retention) {
}
