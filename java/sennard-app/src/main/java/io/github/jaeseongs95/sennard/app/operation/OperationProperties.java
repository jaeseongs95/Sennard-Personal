// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * The values the receipts of the write tools use ({@code sennard.operations.*}).
 *
 * @param receiptRetention how long a receipt answers a request that comes again with its operationKey; an older receipt is removed, and the same key sent after that is a new request
 */
@ConfigurationProperties("sennard.operations")
@Validated
public record OperationProperties(@DefaultValue("24h") @NotNull Duration receiptRetention) {
}
