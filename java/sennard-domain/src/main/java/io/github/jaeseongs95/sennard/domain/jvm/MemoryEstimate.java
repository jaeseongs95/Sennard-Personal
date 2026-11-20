// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.jvm;

/**
 * What the installer worked out from the one memory reading it took.
 *
 * @param observedBytes the total memory the JVM reported
 * @param estimatedGigabytes the smallest standard memory size that is not below it; the machine's RAM is shown as this (an estimate: the JVM reports what the
 *     OS gives it, which is less than what is installed, and a container reports its own limit)
 * @param preset the default preset of that size
 */
public record MemoryEstimate(long observedBytes, long estimatedGigabytes, JvmPreset preset) {
}
