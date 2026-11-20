// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

/**
 * Where the newest stable release of the plugin is asked for. The answer of the network is untrusted input and the call can fail in ways the caller records and goes on from, so
 * {@link #ask} never throws for them: it answers {@link Failed} with the reason.
 */
interface ReleaseSource {
    /** Asks with no earlier answer to compare with. */
    Answer ask();

    /** Asks with the entity tag of the last answer, which lets the source answer {@link NotModified}. */
    Answer askIfChanged(String etag);

    sealed interface Answer permits NotModified, Latest, Failed {
    }

    /** The newest release is the one the last answer named. */
    record NotModified() implements Answer {
    }

    /** The newest stable release: its version ({@code major.minor.patch}) and the commit its tag points at; the answer carried an entity tag ({@link Tagged}) or did not ({@link Untagged}). */
    sealed interface Latest extends Answer permits Tagged, Untagged {
        String version();

        String commit();
    }

    /** The answer carried an entity tag. */
    record Tagged(String version, String commit, String etag) implements Latest {
    }

    /** The answer carried no entity tag, so the next ask is not conditional. */
    record Untagged(String version, String commit) implements Latest {
    }

    /** Why the check found nothing. */
    record Failed(PluginUpdateService.Reason reason) implements Answer {
    }
}
