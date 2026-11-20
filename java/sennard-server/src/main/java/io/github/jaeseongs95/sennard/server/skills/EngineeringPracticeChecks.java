// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The two skills that share {@link EngineeringPractices}. Each keeps its own name so a host can call a skill by the name it knows. */
@Configuration(proxyBeanMethods = false)
public class EngineeringPracticeChecks {
    // @Bean: one delegating SkillCheck class made once per skill name; the same class twice, told apart by a setting.
    @Bean
    SkillCheck<EngineeringPractices.Command> testEngineeringCheck(EngineeringPractices practices) {
        return delegating("test-engineering", practices);
    }

    // @Bean: the second instance of the delegating SkillCheck class, for the other skill name.
    @Bean
    SkillCheck<EngineeringPractices.Command> codeReviewCheck(EngineeringPractices practices) {
        return delegating("code-review", practices);
    }

    private static SkillCheck<EngineeringPractices.Command> delegating(String skill, EngineeringPractices practices) {
        return new SkillCheck<EngineeringPractices.Command>() {
            @Override
            public String skill() {
                return skill;
            }

            @Override
            public Class<EngineeringPractices.Command> commandType() {
                return EngineeringPractices.Command.class;
            }

            /** {@code run} starts the caller's command and waits for it: it leaves the request's thread. */
            @Override
            public Set<EngineeringPractices.Command> backgroundCommands() {
                return EnumSet.of(EngineeringPractices.Command.RUN);
            }

            @Override
            public SkillCheckResult run(EngineeringPractices.Command command, SkillArguments arguments) {
                return practices.run(command, arguments);
            }
        };
    }
}
