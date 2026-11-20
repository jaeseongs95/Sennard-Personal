// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.Objects;

/**
 * Who may read the transcript behind a prompt of the archive: only the session that the prompt was said in. The policy is one value, the calling session ({@code host} and
 * {@code sessionId}, the key the hooks archived the session's prompts under), and one question to it: may this caller read the transcript behind the prompt of that owner?
 * It compares the two sessions and nothing else; no path, no text and no file is its business.
 *
 * <p>The file that is read is never the caller's to name: it is the one the archive's row of the prompt holds ({@code transcript_path}, written by the hook), which the server
 * puts into the {@link TranscriptQuery} itself. The tool has no argument that could carry a path.
 *
 * @param host the calling session's host
 * @param sessionId the calling session's own id, as the host names it
 */
public record TranscriptAccess(String host, String sessionId) {
    /** Why a caller is refused. */
    public enum Reason {
        /** The prompt was said in a session of another host. */
        OTHER_HOST,
        /** The prompt was said in another session of the same host. */
        OTHER_SESSION
    }

    /** The answer of {@link #decide}. */
    public sealed interface Decision {
        /** The caller is the session the prompt was said in. */
        record Granted() implements Decision {
        }

        /** The caller is not. */
        record Refused(Reason reason) implements Decision {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    public TranscriptAccess {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /**
     * @param ownerHost the host of the session the prompt was said in (the archive row's)
     * @param ownerSessionId that session's id (the archive row's)
     */
    public Decision decide(String ownerHost, String ownerSessionId) {
        if (!host.equals(ownerHost)) {
            return new Decision.Refused(Reason.OTHER_HOST);
        }
        if (!sessionId.equals(ownerSessionId)) {
            return new Decision.Refused(Reason.OTHER_SESSION);
        }
        return new Decision.Granted();
    }
}
