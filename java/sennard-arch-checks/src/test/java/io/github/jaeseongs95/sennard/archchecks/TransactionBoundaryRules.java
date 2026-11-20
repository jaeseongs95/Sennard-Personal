// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;

/**
 * Rule u-transaction-boundary: a transaction is opened by a service method and by nothing else. A use case is one public method of a service, and that method is the one
 * transaction; a repository (a store, a Spring Data repository) only joins the transaction of its caller, and an entrance (a hook, a tool, a schedule) opens none and calls one
 * service method. A bean that only exists to hold a transaction for a service is the wrong answer to a service that calls its own method (the proxy is not passed): the use cases
 * are cut again so that one service method is one transaction. The compiled classes of the product are read with ArchUnit, so a use is found wherever it is written.
 *
 * <p>Nine findings, each as {@code Class:line what} or {@code Class.method what} (the first six are {@link #violations}, the last three are held by a ceiling each in
 * {@code ceilings.properties}: {@link #managerByHand}, {@link #connectionByHand}, {@link #noRollback}):
 * <ol>
 * <li>{@code @Transactional} on a method (or on the class) of a class that is not a service ({@code @Service}); a class nested in a service belongs to the service;</li>
 * <li>a dependency on {@code TransactionTemplate} or {@code TransactionOperations}: a transaction opened by hand is not the service method's;</li>
 * <li>a lock made by hand, in a service as well: a method of the product named like a lock ({@code lock} and a capital letter, such as {@code lockModule}), defined or called;
 * concurrency is the database's, by transaction, row lock and constraint;</li>
 * <li>a retry (Spring's {@code @Retryable}, or any annotation of the product that is annotated with it, directly or through others) on a method that is a transaction, or in
 * a class that is one: a transaction that lost is not run again, because the second run decides again on a state the first one's winner changed and does its effects twice (new
 * identifiers, times, signatures, events). The winner of a race is told by the result of one conditional statement;</li>
 * <li>{@code @Transactional(propagation = REQUIRES_NEW)} or {@code NESTED}: a second connection or a savepoint inside a use case is a use case cut wrongly;</li>
 * <li>a propagation set by hand ({@code setPropagationBehavior}, {@code new DefaultTransactionDefinition(int)});</li>
 * <li>{@code u-tx-platform-manager}: a call of {@code PlatformTransactionManager.getTransaction}, {@code commit} or {@code rollback} in the product's main classes;</li>
 * <li>{@code u-tx-jdbc-connection}: a call of {@code java.sql.Connection.setAutoCommit}, {@code commit}, {@code rollback} or {@code setSavepoint} in the product's main classes, except in
 * the classes of {@link Names#connectionExempt()} (the migration runner and the start-up check, each named with its reason, never a whole module);</li>
 * <li>{@code u-tx-no-rollback-for}: {@code noRollbackFor}, {@code noRollbackForClassName} (or {@code dontRollbackOn}) on a transaction annotation: a refusal rolls the whole use case back.</li>
 * </ol>
 *
 * <p>The first six have no exception and no list of exempt classes; the connection rule has the three named classes above and each exemption is proved to be still in use. A command that runs without the server (the admin reset) starts a Spring context of its own and runs its use case as a
 * {@code @Transactional} method of a {@code @Service}, like every other use case.
 */
final class TransactionBoundaryRules {
    private TransactionBoundaryRules() {
    }

    /**
     * What the rule recognises by name, so that the fixtures can use their own annotations and the checks need no Spring on their class path.
     *
     * @param transactional the annotations that open a transaction on a method or a class
     * @param service the annotation of the service layer
     * @param retries the annotations that retry a method; an annotation that is annotated with one of them counts too (the meta-annotation is read, not only the name written)
     * @param manual the types with which a transaction is opened by hand
     * @param definition the class of a transaction definition, whose propagation is set by hand
     * @param owners the package prefix of the classes of the product, whose methods are judged by name for a lock made by hand
     * @param lockMethod the name of a method that is a lock made by hand (a lock row of the product, for example {@code lockModule}); concurrency is the database's, by transaction, row lock and constraint
     * @param manager the transaction manager type and the methods of it that open or end a transaction by hand
     * @param connection the JDBC connection type and the methods of it that open or end a transaction by hand
     * @param connectionExempt the classes (outermost, by full name) that may use those methods of a connection, each with the reason written in one line; there is no exemption by module
     * @param noRollback the properties of a transaction annotation that keep a transaction from rolling back on a failure
     */
    record Names(Set<String> transactional, String service, Set<String> retries, Set<String> manual, String definition, String owners, Pattern lockMethod, Calls manager, Calls connection,
            Map<String, String> connectionExempt, Set<String> noRollback) {
        static final Names PRODUCT = new Names(
                Set.of("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional"),
                "org.springframework.stereotype.Service",
                Set.of("org.springframework.resilience.annotation.Retryable"),
                Set.of("org.springframework.transaction.support.TransactionTemplate", "org.springframework.transaction.support.TransactionOperations"),
                "org.springframework.transaction.support.DefaultTransactionDefinition",
                "io.github.jaeseongs95.sennard.", Pattern.compile("lock[A-Z]\\w*"),
                new Calls("org.springframework.transaction.PlatformTransactionManager", Set.of("getTransaction", "commit", "rollback")),
                new Calls("java.sql.Connection", Set.of("setAutoCommit", "commit", "rollback", "setSavepoint")),
                Map.of(
                        "io.github.jaeseongs95.sennard.service.migration.HistoryMigrator",
                        "applies migration scripts on a connection of its own and commits or rolls back each script with its history row, because DDL ends a transaction in some databases; it is no use case",
                        "io.github.jaeseongs95.sennard.service.store.pg.MigrationRunner",
                        "opens the connection of the migration run (outside every use case) and turns its auto-commit off for HistoryMigrator and the start-up encoding probe",
                        "io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory",
                        "the start-up check runs on a connection of its own, outside every use case: it turns auto-commit off and rolls the encoding probe back, and commits nothing"),
                Set.of("noRollbackFor", "noRollbackForClassName", "dontRollbackOn"));

        /** The same names with no exempt class, to prove that each exemption is still in use. */
        Names withoutExemptions() {
            return new Names(transactional, service, retries, manual, definition, owners, lockMethod, manager, connection, Map.of(), noRollback);
        }
    }

    /**
     * A type and the methods of it that are called.
     *
     * @param type the full name of the type; a call on it or on a subtype is read
     * @param methods the names of the methods
     */
    record Calls(String type, Set<String> methods) {
    }

    /** The ceiling key of the transaction manager rule. */
    static final String RULE_MANAGER = "u-tx-platform-manager";
    /** The ceiling key of the JDBC connection rule. */
    static final String RULE_CONNECTION = "u-tx-jdbc-connection";
    /** The ceiling key of the no-rollback rule. */
    static final String RULE_NO_ROLLBACK = "u-tx-no-rollback-for";

    private static final String SET_PROPAGATION = "setPropagationBehavior";
    private static final String PROPAGATION = "propagation";
    private static final Set<String> NEW_CONNECTION_OR_SAVEPOINT = Set.of("REQUIRES_NEW", "NESTED");

    /** The findings of the rules, for every class. */
    static List<String> violations(JavaClasses classes, Names names) {
        List<String> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            found.addAll(transactionOutsideService(origin, names));
            found.addAll(manualTransaction(origin, names));
            found.addAll(lockMadeByHand(origin, names));
            found.addAll(retryOnTransaction(origin, names));
            found.addAll(propagation(origin, names));
        }
        return found.stream().distinct().sorted().toList();
    }

    /** A call of the transaction manager that opens or ends a transaction by hand, in the classes read. */
    static List<Violation> managerByHand(JavaClasses classes, Names names) {
        List<Violation> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                if (calls(call, names.manager())) {
                    found.add(new Violation(RULE_MANAGER, file(origin), call.getLineNumber(), origin.getName() + " calls " + call.getTargetOwner().getSimpleName() + "." + call.getName()
                            + "(): a transaction opened or ended by hand is not a service method's"));
                }
            }
        }
        return found;
    }

    /** A call of a JDBC connection that opens or ends a transaction by hand, except in the classes named in {@link Names#connectionExempt()}. */
    static List<Violation> connectionByHand(JavaClasses classes, Names names) {
        List<Violation> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (names.connectionExempt().containsKey(outermost(origin).getName())) {
                continue;
            }
            for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                if (calls(call, names.connection())) {
                    found.add(new Violation(RULE_CONNECTION, file(origin), call.getLineNumber(), origin.getName() + " calls Connection." + call.getName()
                            + "(): a transaction made with the connection by hand is not a service method's"));
                }
            }
        }
        return found;
    }

    /** A transaction annotation that says on which failures it does not roll back: a refusal rolls the whole use case back. */
    static List<Violation> noRollback(JavaClasses classes, Names names) {
        List<Violation> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            noRollbackProperty(origin, names).ifPresent(property -> found.add(new Violation(RULE_NO_ROLLBACK, file(origin), 0, origin.getName() + " is @Transactional(" + property
                    + " = ...): a refusal rolls the whole use case back")));
            for (JavaMethod method : origin.getMethods()) {
                noRollbackProperty(method, names).ifPresent(property -> found.add(new Violation(RULE_NO_ROLLBACK, file(origin), method.getSourceCodeLocation().getLineNumber(),
                        origin.getName() + "." + method.getName() + " is @Transactional(" + property + " = ...): a refusal rolls the whole use case back")));
            }
        }
        return found;
    }

    private static boolean calls(JavaMethodCall call, Calls what) {
        return what.methods().contains(call.getName()) && call.getTargetOwner().isAssignableTo(what.type());
    }

    private static String file(JavaClass origin) {
        return outermost(origin).getName().replace('.', '/') + ".java";
    }

    private static Optional<String> noRollbackProperty(HasAnnotations<?> member, Names names) {
        for (String annotation : names.transactional()) {
            Optional<? extends JavaAnnotation<?>> declared = member.tryGetAnnotationOfType(annotation);
            if (declared.isEmpty()) {
                continue;
            }
            for (String property : names.noRollback()) {
                if (declared.get().tryGetExplicitlyDeclaredProperty(property).isPresent()) {
                    return Optional.of(property);
                }
            }
        }
        return Optional.empty();
    }

    /** The class a nested, anonymous or lambda-holding class belongs to: the outermost one. */
    static JavaClass outermost(JavaClass type) {
        JavaClass top = type;
        Optional<JavaClass> around = top.getEnclosingClass();
        while (around.isPresent()) {
            top = around.get();
            around = top.getEnclosingClass();
        }
        return top;
    }

    private static boolean inService(JavaClass type, Names names) {
        return outermost(type).isAnnotatedWith(names.service());
    }

    private static boolean transactional(CanBeAnnotated member, Names names) {
        return names.transactional().stream().anyMatch(member::isAnnotatedWith);
    }

    private static boolean retried(CanBeAnnotated member, Names names) {
        return names.retries().stream().anyMatch(member::isMetaAnnotatedWith);
    }

    private static List<String> transactionOutsideService(JavaClass origin, Names names) {
        List<String> found = new ArrayList<>();
        if (inService(origin, names)) {
            return found;
        }
        if (transactional(origin, names)) {
            found.add(origin.getName() + " is @Transactional but is not a service: a transaction is opened by a service method only");
        }
        for (JavaMethod method : origin.getMethods()) {
            if (transactional(method, names)) {
                found.add(origin.getName() + "." + method.getName() + " is @Transactional but " + origin.getSimpleName() + " is not a service: a transaction is opened by a service method only");
            }
        }
        return found;
    }

    private static List<String> manualTransaction(JavaClass origin, Names names) {
        List<String> found = new ArrayList<>();
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
            String target = dependency.getTargetClass().getFullName();
            if (names.manual().contains(target)) {
                found.add(origin.getName() + ":" + dependency.getSourceCodeLocation().getLineNumber() + " uses " + target + ": a transaction opened by hand is not a service method's");
            }
        }
        return found;
    }

    /** A lock made by hand, in a service as well as outside one: a method of the product named like a lock, defined or called. The database's transaction, row lock and constraint are the lock. */
    private static List<String> lockMadeByHand(JavaClass origin, Names names) {
        List<String> found = new ArrayList<>();
        if (origin.getName().startsWith(names.owners())) {
            for (JavaMethod method : origin.getMethods()) {
                if (names.lockMethod().matcher(method.getName()).matches()) {
                    found.add(origin.getName() + "." + method.getName() + " defines a lock made by hand: concurrency is the database's, by transaction, row lock and constraint");
                }
            }
        }
        for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
            if (call.getTargetOwner().getName().startsWith(names.owners()) && names.lockMethod().matcher(call.getName()).matches()) {
                found.add(origin.getName() + ":" + call.getLineNumber() + " calls " + call.getName() + "(), a lock made by hand: concurrency is the database's, by transaction, row lock and constraint");
            }
        }
        return found;
    }

    private static List<String> retryOnTransaction(JavaClass origin, Names names) {
        List<String> found = new ArrayList<>();
        boolean classRetried = retried(origin, names);
        boolean classTransaction = transactional(origin, names);
        for (JavaMethod method : origin.getMethods()) {
            if ((classRetried || retried(method, names)) && (classTransaction || transactional(method, names))) {
                found.add(origin.getName() + "." + method.getName() + " is retried and is a transaction: a lost transaction is not run again, the winner is told by the result of one conditional statement");
            }
        }
        return found;
    }

    private static List<String> propagation(JavaClass origin, Names names) {
        List<String> found = new ArrayList<>();
        newConnectionOrSavepoint(origin, names).ifPresent(kind -> found.add(origin.getName() + " is @Transactional(propagation = " + kind + "): a use case is one transaction"));
        for (JavaMethod method : origin.getMethods()) {
            newConnectionOrSavepoint(method, names).ifPresent(kind -> found.add(origin.getName() + "." + method.getName() + " is @Transactional(propagation = " + kind + "): a use case is one transaction"));
        }
        for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
            if (SET_PROPAGATION.equals(call.getName()) && call.getTargetOwner().isAssignableTo(names.definition())) {
                found.add(origin.getName() + ":" + call.getLineNumber() + " sets a propagation by hand: a use case is one transaction");
            }
        }
        for (JavaConstructorCall call : origin.getConstructorCallsFromSelf()) {
            if (call.getTargetOwner().isAssignableTo(names.definition()) && call.getTarget().getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(int.class))) {
                found.add(origin.getName() + ":" + call.getLineNumber() + " makes a transaction definition with a propagation by hand: a use case is one transaction");
            }
        }
        return found;
    }

    /** {@code REQUIRES_NEW} or {@code NESTED} when a transaction annotation of the member names it, read from the annotation's written value. */
    private static Optional<String> newConnectionOrSavepoint(HasAnnotations<?> member, Names names) {
        for (String annotation : names.transactional()) {
            Optional<? extends JavaAnnotation<?>> declared = member.tryGetAnnotationOfType(annotation);
            if (declared.isEmpty()) {
                continue;
            }
            Optional<Object> value = declared.get().tryGetExplicitlyDeclaredProperty(PROPAGATION);
            if (value.isPresent() && value.get() instanceof JavaEnumConstant constant && NEW_CONNECTION_OR_SAVEPOINT.contains(constant.name())) {
                return Optional.of(constant.name());
            }
        }
        return Optional.empty();
    }
}
