// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Rule t (TransactionBoundaryRules): a transaction is opened by a service method and by nothing else. The product passes with the rule's zero and with no exception (there is no
 * list of exempt classes); the fixtures prove that each finding goes red and that the near misses stay green.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * t1  the product       the compiled classes of every module -> no finding; the rule recognises the product (services with transaction methods exist, and the admin reset command's use
 *                       case is one of them: a name gone stale would fail here)
 * t2  a repository      RepositoryBad (not a service) has a @Transactional method, calls lockModule() (a lock made by hand) and uses a transaction by hand -> a finding of each kind
 * t3  a bean            ComponentBad (@Transactional on the class, not a service) calls the lock made by hand of a store through the store's own type -> a finding for the class and one for the lock
 * t4  a retry           RetryBad (a service) has three transaction methods retried, through the annotation of the first product retry, the annotation of the second one and the retry
 *                       annotation itself -> a finding each; the same three are found with only the retry annotation on the list (the meta-annotation is read)
 * t4b propagation       PropagationBad (a service) has REQUIRES_NEW and NESTED transaction methods, sets a propagation by hand and makes a definition with one -> four findings
 * t6  by hand           HandMadeBad (a service) calls the manager three times and the connection four times -> seven findings; ExemptConnectionUser (named in the exemptions, with a nested
 *                       class) calls the connection with no finding, and three findings when the exemption is taken away
 * t7  no rollback       HandMadeBad has noRollbackFor on a method, noRollbackForClassName on a method and noRollbackFor on a nested class -> three findings
 * t8  the product       the manager rule, the connection rule and the no-rollback rule are held by the ceilings of u-tx-platform-manager, u-tx-jdbc-connection and u-tx-no-rollback-for (zero);
 *                       every exempt class of the connection rule exists and still calls the connection by hand
 * t5  what is allowed   (the near misses of t6 are in ServiceGood too: Connection.close, a manager's name)  ServiceGood (transaction methods that read a store, one that names REQUIRED, a lambda and a nested class of the service, a retried method that is no transaction, a
 *                       read with no transaction)
 *                       -> no finding
 * </pre>
 */
class TransactionBoundaryRulesTest {
    private static final String FIXTURES = "io.github.jaeseongs95.sennard.archchecks.transactionfixtures";
    private static final String PREFIX = FIXTURES + ".";
    private static final String ADMIN_RESET = "io.github.jaeseongs95.sennard.admincli.AdminReset";

    private static TransactionBoundaryRules.Names fixtureNames() {
        return new TransactionBoundaryRules.Names(Set.of(PREFIX + "FixtureTransactional"), PREFIX + "FixtureService",
                Set.of(PREFIX + "FixtureRetryable", PREFIX + "FixtureRetryOnConflict", PREFIX + "FixtureRetryOnLostRace"), Set.of(PREFIX + "FixtureTemplate"),
                PREFIX + "FixtureDefinition", PREFIX, Pattern.compile("lock[A-Z]\\w*"), new TransactionBoundaryRules.Calls(PREFIX + "FixtureManager", Set.of("getTransaction", "commit", "rollback")),
                new TransactionBoundaryRules.Calls(PREFIX + "FixtureConnection", Set.of("setAutoCommit", "commit", "rollback", "setSavepoint")),
                Map.of(PREFIX + "ExemptConnectionUser", "a fixture that stands for the migration applier"), Set.of("noRollbackFor", "noRollbackForClassName"));
    }

    private static JavaClasses product() {
        List<Path> directories = Stream.of(System.getProperty("sennard.arch.classes").split(",")).map(Path::of).filter(Files::isDirectory).toList();
        assertTrue(directories.size() >= 8, "the compiled classes of the product's modules are read: " + directories);
        return new ClassFileImporter().importPaths(directories);
    }

    private static List<String> about(JavaClasses fixtures, TransactionBoundaryRules.Names names, String className) {
        return TransactionBoundaryRules.violations(fixtures, names).stream().filter(line -> line.startsWith(PREFIX + className + ".") || line.startsWith(PREFIX + className + ":")
                || line.startsWith(PREFIX + className + " ")).toList();
    }

    private static String lines(List<String> found) {
        return found.stream().collect(Collectors.joining("\n    ", "\n    ", ""));
    }

    private static JavaClasses fixtures() {
        return new ClassFileImporter().importPackages(FIXTURES);
    }

    @Test
    void aTransactionOfTheProductIsOpenedByAServiceMethodOnly() {
        JavaClasses classes = product();
        TransactionBoundaryRules.Names names = TransactionBoundaryRules.Names.PRODUCT;
        long services = classes.stream().filter(type -> type.isAnnotatedWith(names.service()) && type.getMethods().stream().anyMatch(method -> names.transactional().stream().anyMatch(method::isAnnotatedWith)))
                .count();
        assertTrue(services >= 20, "the rule recognises the services that have transaction methods of the product: " + services);
        assertTrue(classes.contain("io.github.jaeseongs95.sennard.service.store.jobs.JobService") && classes.contain("io.github.jaeseongs95.sennard.service.store.modules.ModuleStore"), "the job service and the module store base are among the classes read");
        // The offline admin reset is a service like the others: the rule reads its use case (a transaction method of a @Service), so it is judged and not skipped.
        assertTrue(classes.contain(ADMIN_RESET), ADMIN_RESET + " is a class of the product");
        JavaClass adminReset = classes.get(ADMIN_RESET);
        assertTrue(adminReset.isAnnotatedWith(names.service()) && adminReset.getMethods().stream().anyMatch(method -> names.transactional().stream().anyMatch(method::isAnnotatedWith)),
                "AdminReset is a service with a transaction method");
        List<String> found = TransactionBoundaryRules.violations(classes, names);
        assertEquals(List.of(), found, () -> lines(found));
    }

    @Test
    void theProductHasNoTransactionByHandAndNoNoRollbackWithinItsCeilings() {
        JavaClasses classes = product();
        TransactionBoundaryRules.Names names = TransactionBoundaryRules.Names.PRODUCT;
        Ratchet ratchet = new Ratchet();
        ratchet.hold(TransactionBoundaryRules.RULE_MANAGER, TransactionBoundaryRules.managerByHand(classes, names));
        ratchet.hold(TransactionBoundaryRules.RULE_CONNECTION, TransactionBoundaryRules.connectionByHand(classes, names));
        ratchet.hold(TransactionBoundaryRules.RULE_NO_ROLLBACK, TransactionBoundaryRules.noRollback(classes, names));
    }

    @Test
    void everyExemptClassOfTheConnectionRuleExistsAndStillUsesTheConnectionByHand() {
        JavaClasses classes = product();
        TransactionBoundaryRules.Names names = TransactionBoundaryRules.Names.PRODUCT;
        List<Violation> unexempt = TransactionBoundaryRules.connectionByHand(classes, names.withoutExemptions());
        for (var exempt : names.connectionExempt().entrySet()) {
            assertTrue(classes.contain(exempt.getKey()), exempt.getKey() + " is a class of the product: a name gone stale");
            assertTrue(!exempt.getValue().isBlank() && unexempt.stream().anyMatch(violation -> violation.file().equals(exempt.getKey().replace('.', '/') + ".java")),
                    exempt.getKey() + " has a reason and still calls the connection by hand: an exemption that is not used is removed");
        }
    }

    @Test
    void aRepositoryThatOpensItsOwnTransactionTakesTheLockOrOpensOneByHandIsFound() {
        List<String> found = about(fixtures(), fixtureNames(), "RepositoryBad");
        for (String part : List.of("RepositoryBad.write is @Transactional but", "uses " + PREFIX + "FixtureTemplate", "calls lockModule(), a lock made by hand")) {
            assertTrue(found.stream().anyMatch(line -> line.contains(part)), part + " is found in" + lines(found));
        }
    }

    @Test
    void aBeanThatIsTheTransactionOfAServiceIsFound() {
        List<String> found = about(fixtures(), fixtureNames(), "ComponentBad");
        assertTrue(found.stream().anyMatch(line -> line.contains("ComponentBad is @Transactional but is not a service")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("calls lockModule(), a lock made by hand")), lines(found));
    }

    @Test
    void aTransactionThatIsRetriedIsFoundThroughTheAnnotationThatCarriesTheRetryToo() {
        List<String> found = about(fixtures(), fixtureNames(), "RetryBad");
        assertEquals(3, found.size(), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("RetryBad.throughTheProductAnnotation is retried")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("RetryBad.throughTheRetryAnnotation is retried")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("RetryBad.throughTheLostRaceAnnotation is retried")), lines(found));
        // The meta-annotation is read: with only the retry annotation itself on the list, the two annotations that carry it are still found.
        TransactionBoundaryRules.Names onlyTheRetry = new TransactionBoundaryRules.Names(Set.of(PREFIX + "FixtureTransactional"), PREFIX + "FixtureService", Set.of(PREFIX + "FixtureRetryable"),
                Set.of(PREFIX + "FixtureTemplate"), PREFIX + "FixtureDefinition", PREFIX, Pattern.compile("lock[A-Z]\\w*"), fixtureNames().manager(), fixtureNames().connection(),
                fixtureNames().connectionExempt(), fixtureNames().noRollback());
        assertEquals(3, about(fixtures(), onlyTheRetry, "RetryBad").size(), "found through the meta-annotation alone");
    }

    @Test
    void aNewConnectionOrASavepointInsideAUseCaseIsFoundByAnnotationAndByHand() {
        List<String> found = about(fixtures(), fixtureNames(), "PropagationBad");
        assertTrue(found.stream().anyMatch(line -> line.contains("PropagationBad.secondConnection is @Transactional(propagation = REQUIRES_NEW)")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("PropagationBad.savepoint is @Transactional(propagation = NESTED)")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("sets a propagation by hand")), lines(found));
        assertTrue(found.stream().anyMatch(line -> line.contains("makes a transaction definition with a propagation by hand")), lines(found));
        assertEquals(4, found.size(), lines(found));
    }

    @Test
    void aServiceThatKeepsToTheRulesHasNoFinding() {
        JavaClasses good = fixtures().that(com.tngtech.archunit.base.DescribedPredicate.describe("ServiceGood", type -> TransactionBoundaryRules.outermost(type).getSimpleName().equals("ServiceGood")));
        assertTrue(good.stream().map(JavaClass::getSimpleName).collect(Collectors.toSet()).containsAll(Set.of("ServiceGood", "Inner")), "the service and its nested class are read");
        assertEquals(List.of(), TransactionBoundaryRules.violations(good, fixtureNames()));
        assertEquals(List.of(), TransactionBoundaryRules.managerByHand(good, fixtureNames()));
        assertEquals(List.of(), TransactionBoundaryRules.connectionByHand(good, fixtureNames()));
        assertEquals(List.of(), TransactionBoundaryRules.noRollback(good, fixtureNames()));
    }

    private static List<Violation> in(List<Violation> found, String className) {
        return found.stream().filter(violation -> violation.file().endsWith("/" + className + ".java")).toList();
    }

    @Test
    void aTransactionOpenedOrEndedWithTheManagerOrTheConnectionByHandIsFoundAndAnExemptClassIsNot() {
        List<Violation> manager = in(TransactionBoundaryRules.managerByHand(fixtures(), fixtureNames()), "HandMadeBad");
        assertEquals(3, manager.size(), manager::toString);
        for (String call : List.of("getTransaction", "commit", "rollback")) {
            assertTrue(manager.stream().anyMatch(violation -> violation.text().contains("FixtureManager." + call + "()")), call + " is found in " + manager);
        }
        List<Violation> connection = TransactionBoundaryRules.connectionByHand(fixtures(), fixtureNames());
        List<Violation> bad = in(connection, "HandMadeBad");
        assertEquals(4, bad.size(), bad::toString);
        for (String call : List.of("setAutoCommit", "setSavepoint", "commit", "rollback")) {
            assertTrue(bad.stream().anyMatch(violation -> violation.text().contains("Connection." + call + "()")), call + " is found in " + bad);
        }
        assertEquals(List.of(), in(connection, "ExemptConnectionUser"), "the exempt class and its nested class are not found");
        // The exemption is by name and proved in use: with none, the exempt class (and its nested class) is found.
        assertEquals(3, in(TransactionBoundaryRules.connectionByHand(fixtures(), fixtureNames().withoutExemptions()), "ExemptConnectionUser").size());
    }

    @Test
    void aTransactionThatSaysWhichFailuresDoNotRollBackItIsFound() {
        List<Violation> found = in(TransactionBoundaryRules.noRollback(fixtures(), fixtureNames()), "HandMadeBad");
        assertEquals(3, found.size(), found::toString);
        assertTrue(found.stream().anyMatch(violation -> violation.text().contains("keepsTheWriteOnAFailure is @Transactional(noRollbackFor")), found::toString);
        assertTrue(found.stream().anyMatch(violation -> violation.text().contains("keepsTheWriteOnAFailureByName is @Transactional(noRollbackForClassName")), found::toString);
        assertTrue(found.stream().anyMatch(violation -> violation.text().contains("HandMadeBad$WholeClass is @Transactional(noRollbackFor")), found::toString);
    }
}
