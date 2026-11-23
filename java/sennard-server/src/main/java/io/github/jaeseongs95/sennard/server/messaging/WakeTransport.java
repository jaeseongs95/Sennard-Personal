// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchReply;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchResult;
import io.github.jaeseongs95.sennard.server.hook.HostId;

/** A native host doorbell. Neither handoff nor a successful process exit is message delivery, ACK, user authority or completion. */
public interface WakeTransport {
    HostId host();
    /** Target observed on the SessionStarted hook, independent of environment inherited from another conversation. */
    record Target(String hostSessionId, String workspace, BooleanSupplier actorCurrent) {
        static final BooleanSupplier HOOK_ACTOR = () -> true;

        public Target(String hostSessionId, String workspace) {
            this(hostSessionId, workspace, HOOK_ACTOR);
        }
    }

    Availability connect(String registration, Target target);

    sealed interface Availability {
        record Ready(Connection connection) implements Availability {
        }
        record Disabled() implements Availability {
        }
        record Missing(String code) implements Availability {
        }
    }

    enum RelayState {
        OPEN, UNAVAILABLE, CLOSED, OWNER_LOST, EXPIRED
    }

    enum RootReport {
        UNREPORTED, AVAILABLE, DISABLED, MISSING
    }

    /** Connection lifetime and the worker's root capability report are independent. */
    record RootStatus(RelayState relay, RootReport report) {
    }

    @FunctionalInterface
    interface Connection {
        Outcome wake(String hostSessionId, String marker);

        default boolean live() {
            return true;
        }

        /** A local client can be connected even when root wake is unavailable. */
        default boolean registered() {
            return live();
        }

        default boolean managedReady() {
            return false;
        }

        default RootStatus rootStatus() {
            return new RootStatus(RelayState.OPEN, RootReport.UNREPORTED);
        }

        default Outcome wakeManaged(String childId, String marker, BooleanSupplier authorized) {
            return new Outcome.NotSent("CLIENT_MANAGED_RELAY_NOT_READY");
        }

        /** A created thread may be retained by the worker only after the server accepts its native ID. */
        default LaunchReply createChild(String model, String effort, BooleanSupplier ownerCurrent, Predicate<String> bindChild) {
            return new LaunchReply(LaunchResult.NOT_SENT, null);
        }

        default void close() {
        }
    }

    sealed interface Outcome {
        record Submitted() implements Outcome {
        }
        record NotSent(String code) implements Outcome {
        }
        record Unknown(String code) implements Outcome {
        }
    }
}
