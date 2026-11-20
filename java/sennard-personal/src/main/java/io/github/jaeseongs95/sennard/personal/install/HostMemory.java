// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.lang.management.ManagementFactory;

/**
 * The total memory of the machine, read once by the installer. The JVM answers for the OS it runs on, so no command and no OS branch is needed, and
 * inside a container it answers with the container's limit. Nothing reads it again: the server never measures memory when it starts.
 */
@FunctionalInterface
public interface HostMemory {
    /** @return the total physical memory in bytes, the JVM's reading (less than what is installed) */
    long totalBytes();

    static HostMemory ofJvm() {
        return () -> ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class).getTotalMemorySize();
    }
}
