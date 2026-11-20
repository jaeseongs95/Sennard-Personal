// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The values session messaging uses ({@code sennard.messaging.*}): every limit and period of the queue, the drafts and the receipts. The three numbers the public
 * tool contracts state (the body's 4096 bytes, the time to live's 30 to 86400 seconds, the 32768 bytes of a claim answer) can be lowered but not raised: a larger
 * value would pass this check and fail the contract. MessagingPropertiesTest holds the defaults to the contract.
 */
@ConfigurationProperties("sennard.messaging")
@Validated
public record MessagingProperties(
        // contract: contracts/prepare-session-message-request.v1.schema.json (body maxLength 4096, ttlSeconds 30..86400, default 3600); a plain number is seconds, as in the contract
        @DefaultValue("4096") @Min(1) @Max(4096) int bodyMaxBytes,
        @DefaultValue("1h") @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 30) @DurationMax(hours = 24) Duration ttlDefault,
        @DefaultValue("24h") @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 30) @DurationMax(hours = 24) Duration ttlMax,
        // docs/contributing/session-messaging.md: one claim answer is at most 32 KiB
        @DefaultValue("32768") @Min(1) @Max(32768) int claimAnswerMaxBytes,
        @DefaultValue("10") @Min(1) int claimMaxMessages,
        // a draft that is not sent lapses this long after prepare
        @DefaultValue("10m") @NotNull Duration draftTtl,
        // a receipt outlives its message (or its first acknowledgement) by this long; an acknowledged queue row is kept this long too
        @DefaultValue("1h") @NotNull Duration receiptRetention,
        // a claim's lease: the base, doubled for each redelivery (four times), at most the maximum
        @DefaultValue("2m") @NotNull Duration claimLeaseBase,
        @DefaultValue("30m") @NotNull Duration claimLeaseMax,
        @DefaultValue("1000") @Min(1) int draftLimit,
        @DefaultValue("100") @Min(1) int senderDraftLimit,
        @DefaultValue("1000") @Min(1) int receiptLimit,
        @DefaultValue("250") @Min(1) int senderReceiptLimit,
        // the drafts and receipts together, by MessageRules.preparedRecordBytes
        @DefaultValue("4194304") @Min(1) long recordBytesLimit,
        // the queue: unacknowledged messages and their bodies
        @DefaultValue("1000") @Min(1) int messageLimit,
        @DefaultValue("4194304") @Min(1) long messageBytesLimit,
        // peer wait: snapshots kept, and the messages between two sessions a peer wait reads
        @DefaultValue("1000") @Min(1) int peerWaitSnapshotLimit,
        @DefaultValue("20") @Min(1) int peerRelationRows) {

    /** The default time to live is one a message may ask for. */
    @AssertTrue(message = "ttlDefault must not exceed ttlMax")
    public boolean isTtlDefaultWithinMaximum() {
        return ttlDefault.compareTo(ttlMax) <= 0;
    }

    /** A claim lease never starts above its cap. */
    @AssertTrue(message = "claimLeaseBase must not exceed claimLeaseMax")
    public boolean isClaimLeaseBaseWithinMaximum() {
        return claimLeaseBase.compareTo(claimLeaseMax) <= 0;
    }
}
