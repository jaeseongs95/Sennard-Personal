// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A skill a start names as the one it chose, with the reason ({@code skills} of the start inputs). The text is held to what the tool schema states: a skill ID of
 * at most 200 characters that names a skill, and a reason that is not blank and has at most 2000. The ID may be written with a plugin prefix
 * ({@code plugin:skill}); {@link #bareId} is the skill's own name.
 */
record SkillDeclaration(@JsonDeserialize(using = StrictInput.Text.class) @NotNull @Size(max = 200) String skillId,
        @JsonDeserialize(using = StrictInput.Text.class) @NotBlank @Size(max = 2000) String reason) {

    /** The skills of a start input, read from an input that holds more than that. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Declared(@NotNull @Size(min = 1, max = 32) List<@NotNull @Valid SkillDeclaration> skills) {
    }

    /**
     * What a run's receipt keeps of the skills its start declared; {@code advice} is written as {@code null} when the start named no classification.
     * {@code callsNotObserved} is left out until the run is finalized.
     */
    record Recorded(List<SkillDeclaration> skills, @JsonInclude(JsonInclude.Include.ALWAYS) Advice advice, @JsonInclude(JsonInclude.Include.NON_NULL) CallsNotObserved callsNotObserved) {

        /** The same declaration with what finalize found: the declared skills no call was seen for. */
        Recorded withCallsNotObserved(CallsNotObserved found) {
            return new Recorded(skills, advice, found);
        }
    }

    /** The classification a start cites; no advice is available on this server, so the run records that it was not compared. */
    record Advice(String operationKey, boolean available, @JsonInclude(JsonInclude.Include.NON_NULL) List<String> adopted, @JsonInclude(JsonInclude.Include.NON_NULL) List<String> added,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> notAdopted) {

        /** A citation of a classification the server did not compare with the declaration. */
        Advice(String operationKey, boolean available) {
            this(operationKey, available, null, null, null);
        }

        /** The advice the server gave before the start, compared with the skills the start declared: the advised skills it took, the declared ones it added, and the advised ones it left. */
        static Advice compared(ClassificationAdvice.StartAdvice advice, List<SkillDeclaration> declared) {
            List<String> advised = advice.skills().stream().map(ClassificationAdvice.StartAdvice.Skill::skillId).toList();
            List<String> chosen = declared.stream().map(SkillDeclaration::bareId).toList();
            return new Advice(advice.operationKey(), true, advised.stream().filter(chosen::contains).toList(), chosen.stream().filter(skill -> !advised.contains(skill)).toList(),
                    advised.stream().filter(skill -> !chosen.contains(skill)).toList());
        }
    }

    /** The ID names a skill: it is more than a prefix. */
    @JsonIgnore
    @AssertTrue
    boolean isNamed() {
        return skillId == null || !bareId().isEmpty();
    }

    /** The skill's own name: what follows the last prefix separator, without the space around it. */
    String bareId() {
        int end = skillId.length();
        while (end > 0 && skillId.charAt(end - 1) == ':') {
            end--;
        }
        return skillId.substring(skillId.lastIndexOf(':', end - 1) + 1, end).strip();
    }

    /** The declaration without the space around its texts: what the run records. */
    SkillDeclaration stripped() {
        return new SkillDeclaration(skillId.strip(), reason.strip());
    }
}
