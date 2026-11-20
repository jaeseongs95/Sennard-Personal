// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules that read SQL: the migration scripts, and the SQL strings the code hands to {@code JdbcClient.sql(...)} or {@code @Query}
 * (docs/sennard-architecture-checks.md). The database only declares: tables, columns, keys, checks and indexes. Logic objects, row level
 * security and advisory locks go away, and what remains is ANSI SQL. SQL is read as text with the comments and the contents of string literals
 * blanked out, so a word inside a literal or a comment is never counted.
 */
final class SqlRules {
    private SqlRules() {
    }

    /** What the SQL checks know about the schema, read from the migrations (never listed by hand). */
    record Context(Set<String> functionNames) {
        static final Context EMPTY = new Context(Set.of());
    }

    /** One part of an SQL text and the line of the code or script where that part starts. */
    record Piece(String text, int line) {
    }

    /** An SQL statement of the code: its parts in order, as the database receives them once joined. */
    record Statement(List<Piece> pieces, int line) {
        String text() {
            StringBuilder joined = new StringBuilder();
            pieces.forEach(piece -> joined.append(piece.text()));
            return joined.toString();
        }
    }

    enum Kind {
        PG_SYNTAX, RLS, ADVISORY, UDF_CALL, MULTI_USER_COLUMN
    }

    record Hit(String what, int line) {
    }

    // ------------------------------------------------------------------------------------------------------------------ reading text

    /** The text with comments and the contents of quoted literals replaced by spaces; line breaks and offsets are kept. */
    static String mask(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '-' && i + 1 < n && out[i + 1] == '-') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                    out[i++] = ' ';
                }
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    if (out[i] == '\'' && i + 1 < n && out[i + 1] == '\'') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                    } else if (out[i] == '\'') {
                        break;
                    } else {
                        if (out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                    }
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int lineOffset(String text, int offset) {
        int lines = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------------------------------------------------------------- patterns

    private static Pattern word(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static final Map<String, Pattern> PG_SYNTAX = new LinkedHashMap<>();
    private static final Map<String, Pattern> RLS = new LinkedHashMap<>();
    private static final Map<String, Pattern> ADVISORY = new LinkedHashMap<>();
    private static final Map<String, Pattern> MULTI_USER_COLUMN = new LinkedHashMap<>();

    static {
        PG_SYNTAX.put("cast operator ::", word("::\\s*[A-Za-z_\"(]"));
        PG_SYNTAX.put("ILIKE", word("\\bILIKE\\b"));
        PG_SYNTAX.put("ON CONFLICT (standard: MERGE)", word("\\bON\\s+CONFLICT\\b"));
        PG_SYNTAX.put("RETURNING", word("\\bRETURNING\\b"));
        PG_SYNTAX.put("json operator", word("->>?|#>>?|@>|<@"));
        PG_SYNTAX.put("jsonb type or function", word("\\bjsonb\\b|\\bjsonb_[a-z_]+|\\bjson_(?:build|each|extract|array_elements|agg|object_agg|populate)[a-z_]*"));
        PG_SYNTAX.put("pg_* function or catalog", word("\\bpg_(?!try_advisory|advisory)[a-z0-9_]+"));
        PG_SYNTAX.put("full text search", word("\\bto_tsvector\\b|\\bto_tsquery\\b|\\bplainto_tsquery\\b|\\bwebsearch_to_tsquery\\b|\\bphraseto_tsquery\\b|\\bts_(?:rank|headline)[a-z_]*|@@|\\btsvector\\b|\\btsquery\\b"));
        PG_SYNTAX.put("SERIAL (standard: GENERATED ... AS IDENTITY)", word("\\b(?:big|small)?serial\\b"));
        PG_SYNTAX.put("SKIP LOCKED", word("\\bskip\\s+locked\\b"));
        PG_SYNTAX.put("array or set-returning syntax", word("\\bARRAY\\s*\\[|\\bunnest\\s*\\(|\\barray_to_string\\b|\\bany\\s*\\(\\s*\\?\\s*\\)|\\bstring_agg\\b|\\bgenerate_series\\b|\\bDISTINCT\\s+ON\\b"));
        PG_SYNTAX.put("PostgreSQL time function", word("\\bclock_timestamp\\b|\\bstatement_timestamp\\b|\\btransaction_timestamp\\b|\\bnow\\s*\\(\\s*\\)|\\bextract\\s*\\(\\s*epoch\\b"));
        PG_SYNTAX.put("PostgreSQL type name", word("\\bbytea\\b|\\btimestamptz\\b"));
        PG_SYNTAX.put("partial index (CREATE INDEX ... WHERE)", word("\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b[^;]*?\\bWHERE\\b"));
        PG_SYNTAX.put("IF [NOT] EXISTS in DDL", word("\\bIF\\s+(?:NOT\\s+)?EXISTS\\b"));
        RLS.put("row level security policy", word("\\b(?:create|alter|drop)\\s+policy\\b"));
        RLS.put("row level security switch", word("\\b(?:enable|disable|force|no\\s+force)\\s+row\\s+level\\s+security\\b"));
        RLS.put("set_config call", word("\\bset_config\\s*\\("));
        PG_SYNTAX.put("current_setting function", word("\\bcurrent_setting\\s*\\("));
        ADVISORY.put("advisory lock", word("\\bpg_(?:try_)?advisory_[a-z_]+"));
        // The personal edition has one user on one PC: no column or table that tells users, accounts, tenants or namespaces apart. A word is a whole identifier, qualified or not.
        MULTI_USER_COLUMN.put("account column or table", word("(?<![\\w])account(?:_[a-z_0-9]+)?(?![\\w])"));
        MULTI_USER_COLUMN.put("namespace column (ns)", word("(?<![\\w])ns(?![\\w])"));
        MULTI_USER_COLUMN.put("tenant column or table", word("(?<![\\w])tenant(?:_[a-z_0-9]+)?(?![\\w])"));
        MULTI_USER_COLUMN.put("namespace column or table", word("(?<![\\w])namespace(?:_[a-z_0-9]+)?(?![\\w])"));
    }

    private static final Pattern DEFINITION_BEFORE = Pattern.compile("\\bcreate\\s+(?:or\\s+replace\\s+)?function\\s+(?:[\\w\"]+\\.)?$", Pattern.CASE_INSENSITIVE);

    /** A call of one of the user-defined functions the migrations create, qualified by its schema or not. */
    private static Map<String, Pattern> calls(Context context) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String name : context.functionNames()) {
            patterns.put("call of the user-defined function " + name, word("(?<![\\w])(?:[a-z_0-9\"]+\\.)?" + Pattern.quote(name) + "\\s*\\("));
        }
        return patterns;
    }

    /** The hits of one kind in an SQL statement given as parts; the line of a hit is the line of the part it starts in plus the breaks inside that part. */
    static List<Hit> find(List<Piece> pieces, Kind kind, Context context) {
        StringBuilder joined = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (Piece piece : pieces) {
            starts.add(joined.length());
            joined.append(piece.text());
        }
        String masked = mask(joined.toString());
        List<Hit> hits = new ArrayList<>();
        Map<String, Pattern> patterns = switch (kind) {
            case PG_SYNTAX -> PG_SYNTAX;
            case RLS -> RLS;
            case ADVISORY -> ADVISORY;
            case UDF_CALL -> calls(context);
            case MULTI_USER_COLUMN -> MULTI_USER_COLUMN;
        };
        for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
            Matcher matcher = entry.getValue().matcher(masked);
            while (matcher.find()) {
                if (kind == Kind.UDF_CALL && DEFINITION_BEFORE.matcher(masked.substring(Math.max(0, matcher.start() - 60), matcher.start())).find()) {
                    continue; // the name in its own CREATE FUNCTION is the definition, counted by the logic-object rule
                }
                int index = 0;
                while (index + 1 < starts.size() && starts.get(index + 1) <= matcher.start()) {
                    index++;
                }
                String own = pieces.get(index).text();
                int inside = matcher.start() - starts.get(index);
                hits.add(new Hit(entry.getKey(), pieces.get(index).line() + lineOffset(own, Math.min(inside, own.length()))));
            }
        }
        return hits;
    }

    // ----------------------------------------------------------------------------------------------------------------- schema facts

    private static final Pattern CREATE_FUNCTION = word("\\bcreate\\s+(?:or\\s+replace\\s+)?function\\s+([\\w.\"]+)\\s*\\(");

    private static String lastName(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    /** The names of the user-defined functions the migrations create. */
    static Context context(Map<String, String> migrations) {
        Set<String> functions = new LinkedHashSet<>();
        for (String sql : migrations.values()) {
            Matcher function = CREATE_FUNCTION.matcher(mask(sql));
            while (function.find()) {
                functions.add(lastName(function.group(1)));
            }
        }
        return new Context(functions);
    }

    // ---------------------------------------------------------------------------------------------------------------- migration rules

    private static List<Violation> matches(String rule, String file, String sql, Pattern pattern, String what) {
        String masked = mask(sql);
        List<String> lines = sql.lines().toList();
        List<Violation> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(masked);
        while (matcher.find()) {
            int line = lineOffset(masked, matcher.start()) + 1;
            found.add(new Violation(rule, file, line, (line <= lines.size() ? lines.get(line - 1).strip() : "") + "   [" + what + "]"));
        }
        return found;
    }

    private static final Pattern TRIGGER = word("\\bcreate\\s+(?:or\\s+replace\\s+)?(?:constraint\\s+|event\\s+)?trigger\\b|\\breturns\\s+(?:event_)?trigger\\b");

    /** j: CREATE TRIGGER and the functions that return trigger. */
    static List<Violation> triggers(String rule, String file, String sql) {
        return matches(rule, file, sql, TRIGGER, "trigger");
    }

    private static final Pattern LOGIC_OBJECT = word("\\bcreate\\s+(?:or\\s+replace\\s+)?(function|procedure|rule|aggregate|operator)\\b|\\bdo\\s+(?:language\\s+\\w+\\s+)?\\$|\\bdo\\s+'");

    /** l: user-defined functions, procedures, rules, aggregates, operators and DO blocks. A function that returns trigger is counted by j. */
    static List<Violation> logicObjects(String rule, String file, String sql) {
        String masked = mask(sql);
        List<String> lines = sql.lines().toList();
        List<Violation> found = new ArrayList<>();
        Matcher matcher = LOGIC_OBJECT.matcher(masked);
        while (matcher.find()) {
            String header = masked.substring(matcher.start(), Math.min(masked.length(), matcher.start() + 600));
            int body = header.toLowerCase(Locale.ROOT).indexOf("$");
            String signature = body < 0 ? header : header.substring(0, body);
            if (signature.toLowerCase(Locale.ROOT).matches("(?s).*\\breturns\\s+(event_)?trigger\\b.*")) {
                continue;
            }
            int line = lineOffset(masked, matcher.start()) + 1;
            String what = matcher.group(1) == null ? "DO block" : matcher.group(1).toLowerCase(Locale.ROOT);
            found.add(new Violation(rule, file, line, (line <= lines.size() ? lines.get(line - 1).strip() : "") + "   [" + what + "]"));
        }
        return found;
    }

    /** m, n and the calls of l: the hits of one kind in a migration. */
    static List<Violation> migrationHits(String rule, String file, String sql, Kind kind, Context context) {
        List<String> lines = sql.lines().toList();
        List<Violation> found = new ArrayList<>();
        for (Hit hit : find(List.of(new Piece(sql, 1)), kind, context)) {
            found.add(new Violation(rule, file, hit.line(), (hit.line() <= lines.size() ? lines.get(hit.line() - 1).strip() : "") + "   [" + hit.what() + "]"));
        }
        return found;
    }

    // ----------------------------------------------------------------------------------------------------- write boundaries (w)

    /**
     * The verbs the tables that were not read-write once allowed (SELECT only, SELECT and INSERT, SELECT INSERT and UPDATE). The database has one account and no grant,
     * so the code holds the line: a statement of the code writes such a table only with a verb of this list. A table that is not here is fully writable. The rows of the
     * glossary and {@code sennard.module_schema} are written by the migrations only.
     */
    static final Map<String, Set<String>> ALLOWED_WRITES = Map.ofEntries(
            Map.entry("module_schema", Set.of()),
            Map.entry("glossary_metadata", Set.of()),
            Map.entry("glossary_entries", Set.of()),
            Map.entry("glossary_forms", Set.of()),
            Map.entry("user_prompt", Set.of("insert")),
            Map.entry("turn_recap", Set.of("insert", "update")),
            Map.entry("recap_nudge", Set.of("insert")),
            Map.entry("transcript_anchor", Set.of("insert")),
            // The attachments are records: a kept attachment, the files of its bundle and the stored copies of each file are inserted and read, never changed (upload_staging, the upload in progress, is written freely).
            Map.entry("attachment", Set.of("insert")),
            Map.entry("attachment_file", Set.of("insert")),
            Map.entry("attachment_storage", Set.of("insert")));

    /** The one file that writes a table whose words or positions are never changed afterwards (the write path of the user's words is one place). */
    static final Map<String, String> ONLY_WRITER = Map.of("user_prompt", "UserPromptWrites.java", "transcript_anchor", "TranscriptAnchorRepository.java");

    private static final Pattern WRITE = Pattern.compile("^\\s*(?:(insert)\\s+into|(update)|(delete)\\s+from|(merge)\\s+into)\\s+((?:[a-z_0-9]+\\.)?[a-z_0-9]+)");

    /** The problems of one statement of the code against {@link #ALLOWED_WRITES} and {@link #ONLY_WRITER}; {@code file} is the name of the source file it is in. */
    static List<String> writeProblems(String statementText, String file) {
        Matcher write = WRITE.matcher(mask(statementText).toLowerCase(Locale.ROOT));
        if (!write.find()) {
            return List.of();
        }
        String verb = write.group(1) != null ? "insert" : write.group(2) != null ? "update" : write.group(3) != null ? "delete" : "merge";
        String table = lastName(write.group(5));
        List<String> problems = new ArrayList<>();
        Set<String> allowed = ALLOWED_WRITES.get(table);
        if (allowed != null && !allowed.contains(verb)) {
            problems.add(verb.toUpperCase(Locale.ROOT) + " on " + table + " (allowed: " + allowed + ")");
        }
        String writer = ONLY_WRITER.get(table);
        String name = file.replace('\\', '/');
        if (writer != null && !writer.equals(name.substring(name.lastIndexOf('/') + 1))) {
            problems.add(verb.toUpperCase(Locale.ROOT) + " on " + table + " outside " + writer);
        }
        return problems;
    }
}
