// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.util.regex.Pattern;

/** The loopback relay carries capabilities, markers, host launch controls and bounded outcomes. */
public final class NativeWakeProtocol {
    public static final String COMMAND = "native-wake-worker";
    public static final String KEY = "X-Sennard-Wake-Key";
    public static final String MARKER = "X-Sennard-Wake-Marker";
    public static final String CAPABILITIES = "X-Sennard-Worker-Capabilities";
    public static final String ROOT_UNAVAILABLE = "X-Sennard-Root-Wake-Unavailable";
    public static final String LAUNCH_ID = "X-Sennard-Launch-Id";
    public static final String CHILD_ID = "X-Sennard-Child-Id";
    public static final String POLL = "/hooks/v1/native-wake/poll";
    public static final String COMPLETE = "/hooks/v1/native-wake/complete";
    public static final String LAUNCH_COMPLETE = "/hooks/v1/native-wake/launch-complete";
    public static final int INPUT_LIMIT = 1024 * 1024;
    public static final int LAUNCH_LIMIT = 4096;
    private static final Pattern CAPABILITY = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern WAKE_MARKER = Pattern.compile("\\[sennard wake:" + CAPABILITY.pattern() + "\\]");
    private static final Pattern CONVERSATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}");

    private NativeWakeProtocol() {
    }

    public static boolean validKey(String key) {
        return key != null && CAPABILITY.matcher(key).matches();
    }

    public static boolean validMarker(String marker) {
        return marker != null && WAKE_MARKER.matcher(marker).matches();
    }

    public static boolean validConversation(String id) {
        return id != null && CONVERSATION.matcher(id).matches();
    }

    public enum Result {
        SUBMITTED, NOT_SENT, UNKNOWN
    }

    public enum Capability {
        ROOT, MANAGED, BOTH, NONE;

        public boolean rootWake() {
            return this == ROOT || this == BOTH;
        }

        public boolean managedChild() {
            return this == MANAGED || this == BOTH;
        }

        public boolean accepts(RootUnavailable unavailable) {
            return unavailable == null ? this != NONE : !rootWake();
        }
    }

    public enum RootUnavailable {
        DISABLED, MISSING
    }

    public record Launch(String id, String model, String effort) {
        public Launch {
            if (!validKey(id) || !validSelection(model) || !validSelection(effort)) {
                throw new IllegalArgumentException("Invalid managed conversation launch.");
            }
        }

        private static boolean validSelection(String value) {
            return value != null && !value.isBlank() && value.length() <= 200 && value.chars().noneMatch(Character::isISOControl);
        }
    }

    public enum LaunchResult {
        CREATED, SELECTION_UNSUPPORTED, NOT_SENT, UNKNOWN
    }

    public record LaunchReply(LaunchResult result, String childId) {
        public LaunchReply {
            if (result == null || (result == LaunchResult.CREATED
                    ? !validConversation(childId) : childId != null)) {
                throw new IllegalArgumentException("Invalid managed conversation launch result.");
            }
        }
    }
}
