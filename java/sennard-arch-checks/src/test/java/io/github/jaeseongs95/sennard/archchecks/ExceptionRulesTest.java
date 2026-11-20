// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Rules s and t (ExceptionRules): a failure's reason is not a string, and the answer to a failure is a complete switch. The product passes with the rule's ceiling
 * (ArchitectureRulesTest); these fixtures prove that each finding goes red and that the near misses stay green. Case table (sources; expected):
 * <pre>
 * s1  a code literal   new SomeException("A_CODE"), new SomeFailure("A_CODE", "text") and new SomeError("A_CODE") -> one violation each, at its line
 * s2  a qualified type new some.pkg.StoreException("A_CODE", "text") is the same
 * s3  not a code       a sentence, a lower case word, an empty text, a constant, a code enum constant, no argument, a type that is not a failure -> none
 * t1  a default        a switch that names four categories with a default, as a statement or as an expression -> one violation at the switch
 * t2  complete         the same switch with no default -> none; a switch with a default that does not select on a category, or that picks out two of them from a
 *                      RuntimeException (a wrapper, not an answer) -> none
 * t3  counted          the switches that answer the categories are counted whether or not they have a default
 * </pre>
 */
class ExceptionRulesTest {
    private static List<String> codes(String source) {
        return ExceptionRules.stringCodes(JavaSources.parse(Map.of("Sample.java", source))).stream().map(Violation::place).toList();
    }

    private static List<String> defaults(String source) {
        return ExceptionRules.switchDefaults(JavaSources.parse(Map.of("Sample.java", source))).stream().map(Violation::place).toList();
    }

    @Test
    void aCodeWrittenAsALiteralIsAViolationAtItsLine() {
        assertEquals(List.of("Sample.java:2", "Sample.java:3", "Sample.java:4"),
                codes("class Sample {\n    Object a = new SomeException(\"A_CODE\");\n    Object b = new SomeFailure(\"A_CODE\", \"text\");\n    Object c = new SomeError(\"A_CODE_2\");\n}\n"));
    }

    @Test
    void aQualifiedTypeIsRecognisedToo() {
        assertEquals(List.of("Sample.java:2"), codes("class Sample {\n    Object a = new some.pkg.StoreException(\"A_CODE\", \"text\");\n}\n"));
    }

    @Test
    void aSentenceAConstantACodeEnumAndATypeThatIsNoFailureAreNoViolation() {
        assertEquals(List.of(), codes("class Sample {\n    Object a = new SomeException(\"The text of a sentence\");\n    Object b = new SomeException(\"lower_case\");\n"
                + "    Object c = new SomeException(\"\");\n    Object d = new SomeException(CODE);\n    Object e = new SomeException(Code.A_CODE);\n    Object f = new SomeException();\n"
                + "    Object g = new Something(\"A_CODE\");\n}\n"));
    }

    @Test
    void aSwitchOnTheCategoriesWithADefaultIsAViolationAtTheSwitch() {
        String statement = "class Sample {\n    void a(SennardException failure) {\n        switch (failure) {\n" + FOUR.replaceAll("-> \\d;", "-> run();") + "            default -> other();\n        }\n    }\n";
        String expression = "    int b(SennardException failure) {\n        return switch (failure) {\n" + FOUR + "            default -> 2;\n        };\n    }\n}\n";
        assertEquals(List.of("Sample.java:3", "Sample.java:12"), defaults(statement + expression));
    }

    @Test
    void aCompleteSwitchAndASwitchThatIsNoAnswerToTheCategoriesAreNoViolation() {
        String complete = "class Sample {\n    int a(SennardException failure) {\n        return switch (failure) {\n" + FOUR + "        };\n    }\n";
        String other = "    int b(Object value) {\n        return switch (value) {\n            case String text -> 1;\n            default -> 2;\n        };\n    }\n";
        String picksOut = "    int c(RuntimeException failure) {\n        return switch (failure) {\n            case UnavailableException capacity -> 1;\n            case SennardException rejected -> 2;\n"
                + "            default -> 3;\n        };\n    }\n}\n";
        assertEquals(List.of(), defaults(complete + other + picksOut));
    }

    @Test
    void theSwitchesThatAnswerTheCategoriesAreCountedWithOrWithoutADefault() {
        String source = "class Sample {\n    int a(SennardException failure) {\n        return switch (failure) {\n" + FOUR + "        };\n    }\n"
                + "    int b(SennardException failure) {\n        return switch (failure) {\n" + FOUR + "            default -> 2;\n        };\n    }\n}\n";
        assertEquals(2, ExceptionRules.switchesOnCategories(JavaSources.parse(Map.of("Sample.java", source))));
    }

    private static final String FOUR = "            case InvalidInputException input -> 1;\n            case NotFoundException missing -> 2;\n            case ConflictException conflict -> 3;\n"
            + "            case GateRefusedException gate -> 4;\n";
}
