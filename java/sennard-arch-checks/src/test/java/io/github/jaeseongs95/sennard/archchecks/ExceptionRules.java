// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;

/**
 * Rules s and t, how the product raises and answers a failure.
 *
 * <p>Rule s, a failure's reason is not a string: no {@code new <Name>Exception("A_CODE", ...)}, {@code new <Name>Failure("A_CODE")} or {@code new <Name>Error("A_CODE")}, a type whose
 * name ends so and whose first argument is a literal of capital letters, digits and {@code _}. Business failures are an {@code SennardException} of one of its seven categories with a
 * constant of that category's code enum; what the rule leaves is the types that other modules still own (the store's, with its reasons as text).
 * The rule reads names written in the source: a type is a failure by its name.
 *
 * <p>Rule t, one complete answer: a {@code switch} that selects on the seven exception types of the sealed hierarchy ({@code InvalidInputException}, {@code NotFoundException},
 * {@code ConflictException}, {@code GateRefusedException}, {@code BindingException}, {@code IntegrityException}, {@code UnavailableException}; a switch that names at least four of
 * them) has no {@code default}, so a category
 * added to the hierarchy does not compile there until it is answered. Not counted in {@code ceilings.properties}: it must be 0.
 */
final class ExceptionRules {
    static final String STRING_CODE = "s-string-code-exception";
    static final String SWITCH_DEFAULT = "t-sennardexception-switch-default";
    private static final Pattern FAILURE_TYPE = Pattern.compile(".*(Exception|Failure|Error)");
    private static final Pattern CODE_LITERAL = Pattern.compile("[A-Z][A-Z0-9_]*");
    /** The number of categories a switch names before it is taken for an answer to every category. */
    private static final int ANSWERS_AT_LEAST = 4;
    private static final Set<String> CATEGORIES = Set.of("InvalidInputException", "NotFoundException", "ConflictException", "GateRefusedException", "BindingException",
            "IntegrityException", "UnavailableException");

    private ExceptionRules() {
    }

    /** Every {@code new} of a failure type whose first argument is a literal that reads as a code. */
    static List<Violation> stringCodes(List<JavaSources.Unit> sources) {
        List<Violation> found = new ArrayList<>();
        for (JavaSources.Unit unit : sources) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    String type = SourceRules.dotted(node.getIdentifier());
                    if (type != null && FAILURE_TYPE.matcher(simple(type)).matches() && !node.getArguments().isEmpty()) {
                        String first = SourceRules.stringValue(node.getArguments().getFirst());
                        if (first != null && CODE_LITERAL.matcher(first).matches()) {
                            found.add(unit.violation(STRING_CODE, node));
                        }
                    }
                    return super.visitNewClass(node, unused);
                }
            }.scan(unit.tree(), null);
        }
        return found;
    }

    /** Every switch that has a case on one of the seven categories and also a {@code default}. */
    static List<Violation> switchDefaults(List<JavaSources.Unit> sources) {
        List<Violation> found = new ArrayList<>();
        for (JavaSources.Unit unit : sources) {
            switchesOnCategories(unit, (node, hasDefault) -> {
                if (hasDefault) {
                    found.add(unit.violation(SWITCH_DEFAULT, node));
                }
            });
        }
        return found;
    }

    /** How many switches of the sources have a case on one of the seven categories. */
    static int switchesOnCategories(List<JavaSources.Unit> sources) {
        int[] count = {0};
        sources.forEach(unit -> switchesOnCategories(unit, (node, hasDefault) -> count[0]++));
        return count[0];
    }

    /** What is told of each switch that has a case on a category: the switch and whether it has a {@code default}. */
    @FunctionalInterface
    private interface SwitchVisitor {
        void visit(Tree node, boolean hasDefault);
    }

    private static void switchesOnCategories(JavaSources.Unit unit, SwitchVisitor visitor) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitSwitch(SwitchTree node, Void unused) {
                check(node, node.getCases());
                return super.visitSwitch(node, unused);
            }

            @Override
            public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
                check(node, node.getCases());
                return super.visitSwitchExpression(node, unused);
            }

            private void check(Tree node, List<? extends CaseTree> cases) {
                Set<String> seen = new java.util.HashSet<>();
                boolean hasDefault = false;
                for (CaseTree one : cases) {
                    for (CaseLabelTree label : one.getLabels()) {
                        hasDefault |= label instanceof DefaultCaseLabelTree;
                        if (label instanceof PatternCaseLabelTree pattern && pattern.getPattern() instanceof BindingPatternTree binding) {
                            String type = SourceRules.dotted(binding.getVariable().getType());
                            if (type != null && CATEGORIES.contains(simple(type))) {
                                seen.add(simple(type));
                            }
                        }
                    }
                }
                // A switch that answers by category names several of them; one that only picks out one or two (a retry's wrapper over a RuntimeException) is not an answer to every category.
                if (seen.size() >= ANSWERS_AT_LEAST) {
                    visitor.visit(node, hasDefault);
                }
            }
        }.scan(unit.tree(), null);
    }

    private static String simple(String dotted) {
        return dotted.substring(dotted.lastIndexOf('.') + 1);
    }
}
