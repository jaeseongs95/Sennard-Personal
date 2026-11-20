// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The CI of a private repository is started by hand. A workflow of this repository starts on {@code workflow_dispatch} only, except the packaging workflows that are exported to the
 * public repository and start on a published release there.
 *
 * <pre>
 * Case                         Input                                   Expected
 * triggers                     the {@code on} of each workflow file    workflow_dispatch alone, or release (the packaging of a published release)
 * </pre>
 */
class WorkflowTriggersTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final Set<String> ALLOWED = Set.of("workflow_dispatch", "release");

    @Test
    void noWorkflowStartsOnItsOwnOnAPushOrAPullRequest() {
        List<String> automatic = new ArrayList<>();
        int workflows = 0;
        for (String path : REPO.trackedFiles()) {
            if (isWorkflowFile(path)) {
                workflows++;
                Map<?, ?> document = (Map<?, ?>) new Yaml().load(REPO.text(path));
                // YAML 1.1 reads the key `on` as the boolean true.
                Object triggers = document.containsKey("on") ? document.get("on") : document.get(Boolean.TRUE);
                List<String> names = switch (triggers) {
                    case Map<?, ?> map -> map.keySet().stream().map(String::valueOf).toList();
                    case List<?> list -> list.stream().map(String::valueOf).toList();
                    case String name -> List.of(name);
                    case null, default -> List.of("(none)");
                };
                names.stream().filter(name -> !ALLOWED.contains(name)).forEach(name -> automatic.add(path + " starts on " + name));
            }
        }
        assertFalse(workflows == 0, "the workflows are found");
        assertEquals(List.of(), automatic, "a workflow starts without being asked to");
    }

    private static final Pattern PINNED = Pattern.compile("^[^@\\s]+@[0-9a-f]{40}$");

    /**
     * The one condition the user decided for a job that a release starts: it runs when the event is not a release, or when the repository is not private. Compared as a whole
     * expression with the white space removed, not as a part of a string, because {@code true || !github.event.repository.private} contains the guard and holds it back from nothing.
     */
    private static final String RELEASE_GUARD = "github.event_name!='release'||!github.event.repository.private";

    /** A workflow file of GitHub Actions: both extensions that GitHub reads. */
    private static boolean isWorkflowFile(String path) {
        return path.startsWith(".github/workflows/") && (path.endsWith(".yml") || path.endsWith(".yaml"));
    }

    /**
     * Whether {@code condition} (the {@code if} of a job) holds the job back in a private repository: the whole expression is the release guard, or the release guard in
     * parentheses {@code &&} a rest that has no {@code ||} outside parentheses (a conjunction can only narrow the guard). A missing condition, a boolean, text around a
     * {@code ${{ }}} (GitHub reads that as a string that is always true) and every other shape do not.
     */
    static boolean heldBackInAPrivateRepository(Object condition) {
        if (!(condition instanceof String text)) {
            return false;
        }
        String expression = text.strip();
        if (expression.startsWith("${{")) {
            if (!expression.endsWith("}}")) {
                return false;
            }
            expression = expression.substring(3, expression.length() - 2);
        }
        if (expression.contains("${{") || expression.contains("}}")) {
            return false;
        }
        String compact = expression.replaceAll("\\s+", "");
        if (compact.equals(RELEASE_GUARD)) {
            return true;
        }
        String guarded = "(" + RELEASE_GUARD + ")&&";
        return compact.startsWith(guarded) && compact.length() > guarded.length() && !hasAlternativeOutsideParentheses(compact.substring(guarded.length()));
    }

    private static boolean hasAlternativeOutsideParentheses(String expression) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                depth--;
            } else if (!quoted && depth == 0 && expression.startsWith("||", index)) {
                return true;
            }
        }
        return depth != 0 || quoted;
    }

    @Test
    void theReleaseGuardIsComparedAsAWholeExpression() {
        for (String held : List.of(
                "${{ github.event_name != 'release' || !github.event.repository.private }}",
                "github.event_name != 'release' || !github.event.repository.private",
                "${{ (github.event_name != 'release' || !github.event.repository.private) && (github.event.release.tag_name != '' || inputs.tag != '') }}",
                "${{ (github.event_name != 'release' || !github.event.repository.private) && inputs.tag != '' }}")) {
            assertTrue(heldBackInAPrivateRepository(held), held);
        }
        for (String open : List.of(
                "${{ true || !github.event.repository.private }}",
                "${{ !github.event.repository.private }}",
                "${{ !github.event.repository.private || true }}",
                "${{ github.event_name != 'release' || !github.event.repository.private || true }}",
                "${{ (github.event_name != 'release' || !github.event.repository.private) || inputs.tag != '' }}",
                "${{ (github.event_name != 'release' || !github.event.repository.private) && true || true }}",
                "${{ github.event_name != 'release' || !github.event.repository.private }} || true",
                "${{ github.event_name != 'release' || github.event.repository.private }}",
                "${{ github.event_name != 'release' || !github.event.repository.private && false }}",
                "${{ 'x' && !github.event.repository.private }}",
                "!github.event.repository.private",
                "")) {
            assertFalse(heldBackInAPrivateRepository(open), open);
        }
        assertFalse(heldBackInAPrivateRepository(null), "a job with no condition");
        assertFalse(heldBackInAPrivateRepository(Boolean.TRUE), "a condition that is the boolean true");
    }

    @Test
    void bothYamlExtensionsAreWorkflowFiles() {
        assertTrue(isWorkflowFile(".github/workflows/a.yml"));
        assertTrue(isWorkflowFile(".github/workflows/a.yaml"));
        assertFalse(isWorkflowFile(".github/workflows/a.json"));
        assertFalse(isWorkflowFile(".github/DISCUSSION_TEMPLATE/a.yml"));
    }

    /** Every tracked workflow file by path, as the YAML document it holds. */
    private static Map<String, Map<?, ?>> workflows() {
        Map<String, Map<?, ?>> found = new LinkedHashMap<>();
        for (String path : REPO.trackedFiles()) {
            if (isWorkflowFile(path)) {
                found.put(path, (Map<?, ?>) new Yaml().load(REPO.text(path)));
            }
        }
        assertFalse(found.isEmpty(), "the workflows are found");
        return found;
    }

    private static Object triggers(Map<?, ?> document) {
        // YAML 1.1 reads the key `on` as the boolean true.
        return document.containsKey("on") ? document.get("on") : document.get(Boolean.TRUE);
    }

    private static boolean startsOnRelease(Map<?, ?> document) {
        return switch (triggers(document)) {
            case Map<?, ?> map -> map.containsKey("release");
            case List<?> list -> list.contains("release");
            case String name -> "release".equals(name);
            case null, default -> false;
        };
    }

    private static Map<?, ?> jobs(Map<?, ?> document) {
        return (Map<?, ?>) document.get("jobs");
    }

    private static List<?> steps(Object job) {
        Object steps = ((Map<?, ?>) job).get("steps");
        return steps == null ? List.of() : (List<?>) steps;
    }

    @Test
    void everyJobOfAWorkflowThatStartsOnAReleaseIsHeldBackInAPrivateRepository() {
        List<String> unguarded = new ArrayList<>();
        int releaseWorkflows = 0;
        for (Map.Entry<String, Map<?, ?>> workflow : workflows().entrySet()) {
            if (startsOnRelease(workflow.getValue())) {
                releaseWorkflows++;
                jobs(workflow.getValue()).forEach((name, job) -> {
                    if (!heldBackInAPrivateRepository(((Map<?, ?>) job).get("if"))) {
                        unguarded.add(workflow.getKey() + " job " + name + " has the condition " + ((Map<?, ?>) job).get("if"));
                    }
                });
            }
        }
        assertFalse(releaseWorkflows == 0, "the packaging workflows start on a release");
        assertEquals(List.of(), unguarded, "a job of a release workflow is not held back in a private repository by exactly the release guard");
    }

    @Test
    void everyActionIsPinnedToACommitSha() {
        List<String> loose = new ArrayList<>();
        for (Map.Entry<String, Map<?, ?>> workflow : workflows().entrySet()) {
            jobs(workflow.getValue()).forEach((name, job) -> {
                // A job that calls a reusable workflow has its own {@code uses}, which GitHub resolves like an action's.
                List<Object> used = new ArrayList<>();
                used.add(((Map<?, ?>) job).get("uses"));
                for (Object step : steps(job)) {
                    used.add(((Map<?, ?>) step).get("uses"));
                }
                for (Object uses : used) {
                    if (uses != null && !String.valueOf(uses).startsWith("./") && !PINNED.matcher(String.valueOf(uses)).matches()) {
                        loose.add(workflow.getKey() + " job " + name + " uses " + uses);
                    }
                }
            });
        }
        assertEquals(List.of(), loose, "an action is not pinned to a 40-character commit SHA");
    }

    @Test
    void everyCheckoutKeepsNoCredentials() {
        List<String> keeping = new ArrayList<>();
        int checkouts = 0;
        for (Map.Entry<String, Map<?, ?>> workflow : workflows().entrySet()) {
            for (Map.Entry<?, ?> job : jobs(workflow.getValue()).entrySet()) {
                for (Object step : steps(job.getValue())) {
                    Map<?, ?> map = (Map<?, ?>) step;
                    if (map.get("uses") != null && String.valueOf(map.get("uses")).startsWith("actions/checkout@")) {
                        checkouts++;
                        Object with = map.get("with");
                        Object persist = with == null ? null : ((Map<?, ?>) with).get("persist-credentials");
                        if (!"false".equals(String.valueOf(persist))) {
                            keeping.add(workflow.getKey() + " job " + job.getKey());
                        }
                    }
                }
            }
        }
        assertFalse(checkouts == 0, "the checkouts are found");
        assertEquals(List.of(), keeping, "a checkout lacks persist-credentials: false");
    }
}
