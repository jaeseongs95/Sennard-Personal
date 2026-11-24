// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The layout of the admin page, held where a file can hold it. The page is one static file and the browser draws it, so what is checked is what the file says about its
 * structure (the user's finding on the open roots, JEV and database screens: lists stacked with the browser's indent, a card with an empty column, a warning band floating
 * in the middle, actions at the bottom left, screen actions below the list, a ready line whose parts ran together; and on the sessions: what a session does must be read first):
 *
 * <pre>
 * l1  every definition list is made by one helper, and the stylesheet makes it a two-column grid with the browser's indent of dd off, one column on a narrow window
 * l2  a value that is empty is the catalog's "None" (the helper), never an empty cell
 * l3  the cards of an open root and of an approval request are made by one panel (head, body, notice, actions), and the actions are right-aligned
 * l4  the reload of the open roots is in the head of its screen, before the list
 * l5  the conditions of JEV are the rows of one box, not a card each
 * l6  the ready note keeps its lines apart
 * l7  a session row reads summary, on what basis, role with its action, then the small print; a missing or old summary is said in words next to the action
 * l8  the page keeps nothing in the browser (the origin changes with the port, and the PC is the user's)
 * l10 the header has the language switch (see theHeaderHasALanguageSwitchThatKeepsWhatTheUserBegan)
 * l9  the passkey screen shows the one passkey and the way back when it is lost; it adds and deletes none
 * l11 a present user title goes before the existing summary as a text node; an absent title adds no element
 * </pre>
 */
class AdminPageLayoutTest {
    private static final String PAGE = page();

    private static String page() {
        try (InputStream in = new ClassPathResource("admin/admin-page.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("the admin page is not in the classpath", unreadable);
        }
    }

    private static String style() {
        return PAGE.substring(PAGE.indexOf("<style"), PAGE.indexOf("</style>"));
    }

    /** The source of one function of the page: from its declaration to the declaration of the function that follows it. */
    private static String function(String name, String next) {
        int from = PAGE.indexOf("function " + name + "(");
        assertTrue(from >= 0, "the page has function " + name);
        int to = PAGE.indexOf("function " + next + "(", from);
        assertTrue(to > from, "function " + name + " is followed by function " + next);
        return PAGE.substring(from, to);
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            found++;
        }
        return found;
    }

    /** The declarations of one rule that begins a line of the stylesheet. */
    private static String rule(String selector) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\{([^}]*)\\}").matcher(style());
        assertTrue(matcher.find(), "the stylesheet has the rule " + selector);
        return matcher.group(1);
    }

    @Test
    void everyDefinitionListIsMadeByOneHelper() {
        assertEquals(1, count(PAGE, "h('dl'"), "a definition list is made by kv() and nowhere else");
        assertEquals(1, count(PAGE, "h('dt'"));
        assertEquals(1, count(PAGE, "h('dd'"));
        assertFalse(PAGE.contains("<dl"), "no list is written as markup, where the browser's own indent would show");
    }

    @Test
    void aDefinitionListIsATwoColumnGridWithoutTheBrowsersIndentAndOneColumnWhenNarrow() {
        String dl = rule("dl");
        assertTrue(dl.contains("display:grid") && dl.contains("grid-template-columns:10rem minmax(0,1fr)"), dl);
        assertTrue(rule("dd").contains("margin:0"), "the browser indents dd; the list takes it off");
        assertTrue(rule("dt").contains("color:var(--muted)"), "a label is muted");
        int narrow = style().indexOf("@media (max-width:640px){");
        assertTrue(narrow > 0, "a narrow window has its own rules");
        String narrowRules = style().substring(narrow, style().indexOf("@media (max-width:760px)"));
        assertTrue(narrowRules.contains("dl{grid-template-columns:minmax(0,1fr)"), "one column on a narrow window");
    }

    @Test
    void anEmptyValueIsTheCatalogsNoneNotAnEmptyCell() {
        String kv = function("kv", "panel");
        assertTrue(kv.contains("isBlank(value)") && kv.contains("'admin.page.common.none'"), kv);
    }

    @Test
    void theCardsOfARootAndOfAnApprovalAreOnePanelWithRightAlignedActions() {
        String root = function("rootCard", "viewRoots");
        String approval = function("approvalCard", "viewApprovals");
        for (String card : new String[] {root, approval}) {
            assertTrue(card.contains("panel({") && card.contains("body:") && card.contains("acts:") && card.contains("status:"), card);
            assertFalse(card.contains("class: 'card'"), "the row card of the sessions is not the card of a thing to act on");
        }
        assertTrue(root.contains("notice:"), "the owner warning is the notice of the card, the whole width");
        assertFalse(root.contains("class: 'band"), "a band is the page's, not a card's");
        int from = PAGE.indexOf("function panel(");
        String panel = PAGE.substring(from, from + 600);
        int head = panel.indexOf("panel-head"), body = panel.indexOf("panel-body"), notice = panel.indexOf("o.notice"), acts = panel.indexOf("panel-acts"), status = panel.indexOf("o.status");
        assertTrue(head > 0 && head < body && body < notice && notice < acts && acts < status, "head, body, notice, actions, status: " + panel);
        assertTrue(rule(".panel-acts").contains("justify-content:flex-end"), "the actions are at the right of the card");
    }

    @Test
    void theReloadOfTheOpenRootsIsInTheHeadOfItsScreen() {
        String roots = function("viewRoots", "gMax");
        int declared = roots.indexOf("const reload"), head = roots.indexOf("screenHead('roots-title'"), list = roots.indexOf("list.length");
        assertTrue(declared >= 0 && head > declared && list > head, roots);
        assertTrue(roots.substring(head, list).contains("reload"), "the reload is an action of the head, given before the list");
    }

    @Test
    void jevHasOneReadinessSummaryAndOneSwitchForSkillAdvice() {
        String readiness = function("jevReadiness", "saveJev");
        String settings = function("viewJev", "usageLine");
        assertTrue(readiness.contains("role: 'status'") && readiness.contains("j.ready"));
        assertFalse(PAGE.contains("jev-checks-title"));
        assertTrue(settings.contains("id: 'jev-on'") && settings.contains("cur.presets[skillPreset] = sw.checked"));
        assertFalse(settings.contains("h('table'"));
    }

    @Test
    void generalSettingsExposeOnlyTheWorkflowLimitAndThreeAttachmentFields() {
        String attachments = function("viewAttachmentStorage", "viewResourceLimits");
        assertTrue(attachments.contains("['directory', 'maxFileBytes', 'attachmentBudgetBytes']"));
        assertTrue(attachments.contains("{ ...saved, ...Object.fromEntries"));
        String limits = function("viewResourceLimits", "jvmLabel");
        assertFalse(limits.contains("rl-cs-bytes"));
        assertTrue(limits.contains("limits: { ...limits, maxOpenWorkflowsPerSession: parse() }"));
    }

    @Test
    void theReadyNoteKeepsItsLinesApart() {
        String note = rule(".note-ok");
        assertTrue(note.contains("display:flex") && note.contains("flex-direction:column"), note);
    }

    @Test
    void aSessionRowReadsWhatItDoesThenItsRoleWithTheActionThenTheSmallPrint() {
        String row = function("sessionCard", "sessionLine");
        int summary = row.indexOf("'doing'"), basis = row.indexOf("'s-basis'"), role = row.indexOf("'s-role '"), acts = row.indexOf("'s-acts'"), thin = row.indexOf("'admin.page.bindings.thinBasis'"),
                meta = row.indexOf("'s-meta'");
        assertTrue(summary > 0 && summary < basis && basis < role && role < acts && acts < thin && thin < meta, "summary, basis, role, actions, what is missing, small print: " + row);
        for (String key : new String[] {"basisNoSummary", "basisNoRequest", "basisStale", "basisFresh"}) {
            assertTrue(row.contains("'admin.page.bindings." + key + "'"), "the basis of a summary is said in words: " + key);
        }
        assertTrue(row.contains("s.summaryStale === true"), "the server's own judgment of an old summary is what the row shows");
        assertTrue(row.contains("aria-hidden"), "the mark is for the eye; the words carry the meaning");
    }

    @Test
    void aSessionCardAddsTheUserTitleAsTextOnlyWhenPresent() {
        String card = function("sessionCard", "sessionLine");
        Matcher title = Pattern.compile("s\\.userTitle\\s*\\?\\s*h\\([^,]+,\\s*(?:\\{[^}]*\\}|null),\\s*s\\.userTitle\\s*\\)\\s*:\\s*null")
                .matcher(card);
        assertTrue(title.find(), "a present title is a child of the DOM helper and absence adds no element");
        assertTrue(title.start() < card.indexOf("'doing'"), "the title precedes the existing summary");
        assertTrue(function("h", "fmtTime").contains("document.createTextNode(String(c))"), "the DOM helper appends user text literally");
    }

    /** l10  the header has the language switch: both names, the current one shown, buttons a keyboard reaches; a change asks before it takes unsaved edits and never races the refresh. */
    @Test
    void theHeaderHasALanguageSwitchThatKeepsWhatTheUserBegan() {
        String control = function("languageControl", "renderTop");
        assertTrue(control.contains("role: 'group'") && control.contains("'aria-label': t('admin.page.top.language')"), "the switch is a named group");
        assertTrue(control.contains("h('button', { type: 'button'") && control.contains("'aria-pressed'"), "native buttons, the current language pressed");
        assertTrue(control.contains("lang: code"), "each name is marked with its own language");
        assertTrue(PAGE.contains("['ko', 'admin.page.top.languageKo']") && PAGE.contains("['en', 'admin.page.top.languageEn']"), "both names are catalog keys");
        assertTrue(PAGE.contains("h('div', { class: 'grow' }), languageControl()"), "the control is in the header");
        String choose = function("chooseLanguage", "languageControl");
        assertTrue(choose.contains("hasPending() || S.busy || S.dialog") && choose.contains("window.confirm(t('admin.page.top.languageConfirm'))"), "unsaved work is asked about first");
        assertTrue(choose.indexOf("window.confirm") < choose.indexOf("'/api/language'"), "nothing is sent before the user agreed");
        assertTrue(choose.contains("clearTimeout(pollTimer)") && function("pollBlocked", "openDetails").contains("languageSwitching"), "the 3-second refresh stands still while the language changes");
        assertTrue(PAGE.contains("hasUnsaved() && !languageSwitching"), "the browser does not ask a second time after the user agreed");
        assertTrue(style().contains(".lang .btn[aria-pressed=\"true\"]"), "the current language is marked by more than colour: weight and fill");
    }

    /**
     * The workflow statistics screen: w1 the period totals precede the newest starts, followed by the on-demand skills of each kind;
     * w2 a period with no start says so and shows no empty tables; w3 the screen is read again by the page's refresh from its own endpoint; w4 the kinds and the states come from the
     * server's names and the sentences from the catalog, so the page names no kind; w5 on a narrow window a table scrolls inside its box (the page does not).
     */
    @Test
    void theWorkflowStatisticsReadTotalsThenTheNewestStartsThenTheSkillsOfEachKind() {
        String view = function("viewWorkflows", "viewGeneral");
        int kinds = view.indexOf("viewKindsTable(d)"), skills = view.indexOf("viewKindSkills(d)"), recent = view.indexOf("viewRecentWorkflows(d)");
        assertTrue(kinds >= 0 && recent > kinds && skills > recent, "w1: period totals, then the newest starts, then the skills of each kind");
        assertTrue(view.indexOf("if (!d.total)") >= 0 && view.indexOf("if (!d.total)") < kinds, "w2: the empty period is told before any table");
        String poll = function("pollOnce", "pollLoop");
        assertTrue(poll.contains("'/api/workflow-stats?days='") && poll.contains("S.w.data = r.json"), "w3: the refresh reads the screen's own endpoint");
        for (String kind : new String[] {"INQUIRY", "CHANGE", "OPERATION"}) {
            assertFalse(PAGE.contains("'" + kind + "'"), "w4: the page does not name the kind " + kind);
        }
        assertTrue(PAGE.contains(".tbl-wrap{overflow-x:auto") && PAGE.contains(".tbl-wrap.narrow table{min-width:0}"), "w5: a table scrolls inside its box");
        assertTrue(function("viewKindSkills", "viewRecentWorkflows").contains("aria-labelledby"), "each kind's table is named by its heading");
    }

    @Test
    void thePageKeepsNothingInTheBrowser() {
        for (String store : new String[] {"localStorage", "sessionStorage", "indexedDB", "document.cookie"}) {
            assertFalse(PAGE.contains(store), "the page's address (the port) changes at every start, so what a browser stored is gone: " + store);
        }
        assertFalse(PAGE.contains("admin.page.bands.notMine") || PAGE.contains("admin.page.bands.missing"), "no warning that rests on a record of this browser");
    }

    @Test
    void sessionBindingSearchUsesTheSelectedScopeBeforeShowingResults() {
        String view = function("viewBindings", "sessionCard");
        assertTrue(view.contains("workSearch('bindings-query'"), "bindings reuse the search field with keyboard and IME handling");
        assertTrue(view.indexOf(".filter(pick)") < view.indexOf("matchesBindingSearch("), "search applies within the selected scope");
        assertTrue(view.indexOf("matchesBindingSearch(") < view.indexOf(".slice(0, S.b.limit)"), "search covers rows beyond the first page");
        assertTrue(view.contains("'admin.page.bindings.noMatches'") && view.contains("'bindings-reset'"), "a search with no results can be cleared");
    }

    @Test
    void workflowManagementFiltersWorkflowAndLatestRunStatesSeparately() {
        String view = function("viewRoots", "gMax");
        assertTrue(view.contains("workSelect('roots-state'") && view.contains("workSelect('roots-run-state'"), "both states have independent controls");
        assertTrue(view.contains("r.state === rr.state") && view.contains("latestRunState(r) === rr.runState"), "both filters contribute to the match");
        assertTrue(view.contains("rt('empty')") && view.contains("rt('noMatches')"), "empty data and filtered results are distinguished");
        assertTrue(function("releaseRoot", "confirmBlock").contains("expectedRevision: r.revision"), "release signs the checked revision");
    }

    @Test
    void blockedWorkflowPlansHaveTheirOwnNavigationAndKeepSignedUnblocking() {
        assertTrue(PAGE.contains("{ key: 'blocked-plans', label: t('admin.page.nav.blockedPlans') }"), "blocked plans have a navigation item");
        assertTrue(function("renderMain", "viewApprovals").contains("'blocked-plans': viewBlockedPlans"), "the menu routes to the blocked-plans view");
        assertFalse(function("viewGeneral", "viewAttachmentStorage").contains("viewBlockedPlans"), "the list is removed from general settings");
        String view = function("viewBlockedPlans", "viewDatabase");
        assertTrue(view.contains("head('blocked-plans-title'") && view.contains("message('empty')"), "the screen has a main heading and an empty state");
        assertTrue(view.contains("op: 'WORKFLOW_PLAN_UNBLOCK'") && view.contains("recordId"), "unblocking signs the current plan record");
    }

    @Test
    void theShownScreenRefreshesItselfEveryThreeSecondsWithoutTakingAnythingFromTheUser() {
        assertTrue(PAGE.contains("const POLL_MS = 3000;"), "the refresh runs every 3 seconds");
        String poll = function("pollOnce", "pollLoop");
        assertTrue(poll.contains("'/api/skill-usage?days='") && poll.contains("'/api/state'"), "the statistics read their own list, every other screen the page state");
        assertTrue(poll.contains("render(S.tab === tab)"), "only the shown screen is drawn again");
        assertTrue(poll.contains("text === pollText"), "nothing is drawn when the data did not change");
        assertTrue(poll.contains("loadSeq !== stateSeq") && poll.contains("S.tab !== tab"), "a read that another screen or another read overtook is dropped");
        assertTrue(poll.contains("pollRunning"), "reads do not overlap");
        assertTrue(poll.contains("if (!pollFailed"), "a failure is told once");
        String blocked = function("pollBlocked", "openDetails");
        for (String skip : new String[] {"document.hidden", "S.busy", "S.dialog", "dialog[open]", "linkWatching", "hasPending()", "languageSwitching", "TEXT_FIELD"}) {
            assertTrue(blocked.contains(skip), "the refresh is skipped on " + skip);
        }
        assertFalse(poll.contains("location.reload"), "the refresh never reads the page again as a whole");
        assertEquals(1, count(PAGE, "location.reload"), "only the language change draws the whole page again");
        assertTrue(PAGE.contains("return hasUnsaved() || !!S.newKeyLabel.trim()"), "what is pending includes the unsaved edits");
        assertTrue(PAGE.contains("setTimeout(async () => { try { await pollOnce();"), "the next read is timed after the last one ended");
        assertFalse(poll.contains("aria-live"), "a refresh is not announced again");
        assertTrue(function("render", "renderTop").contains("activeOnly"), "the top bar and the save bar are left alone by the refresh");
    }

    @Test
    void thePasskeyScreenIsOnePasskeyAndTheWayBackWhenItIsLost() {
        String screen = function("viewPasskey", "hasUnsaved");
        assertTrue(screen.contains("'admin.page.passkey.lostHint'") && screen.contains("sennard admin-reset"), "the way back from a lost passkey is one line");
        assertTrue(screen.contains("if (keys.length)") && screen.indexOf("if (keys.length)") < screen.indexOf("registerKey"), "the form to register is there only while there is no passkey");
        assertFalse(PAGE.contains("'KEY_REVOKE'"), "the page deletes no passkey");
        assertFalse(PAGE.contains("admin.page.passkey.addMore"), "the page adds no further passkey");
    }
}
