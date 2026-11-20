// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import io.github.jaeseongs95.sennard.service.store.Encrypted;
import io.github.jaeseongs95.sennard.service.store.pg.TestSealer;

/**
 * The cases of {@link EncryptedRecords}, the common path of the {@link Encrypted} columns, with a real AES-GCM sealer ({@link TestSealer}) and no database. The one stored form of the
 * JEV key that existed before the annotation is the case of sennard-personal ({@code JevKeyStoredValueCompatTest}), which has the edition's sealer.
 *
 * <pre>
 * e1  sealed on write         the column of an encrypted component holds no plain text; the others are written as they are, in the record's order, in snake case or the declared column
 * e2  opened on read          the sealed value reads back as the original, the other components unchanged
 * e3  bound to its row        a value moved to another name or another bound value does not open (SecretSealException); the same row opens it
 * e4  a fresh value each time the same value seals to different text (a random IV)
 * e5  null                    a null component is null written and read, a null row is a null row, a record with no encrypted component is returned as it is
 * e6  wrong declaration       a component that is not a String, a missing or encrypted or reserved bound component and a blank scope fail with the component named
 * e7  once per class          the declarations of a class are the same object on every call
 * e8  no sealer               an application without one fails when an encrypted value is written or read (never the clear value written)
 * e9  a bound null            a null bound component is refused when a value is sealed (the value would be bound to nothing)
 * e10 the store starts        the store's nested records are checked at start: a wrong one stops it, the context store's own pass
 * </pre>
 */
class EncryptedRecordsTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    record Credential(String id, String fingerprint, @Encrypted(scope = "test.credential", name = "id", bind = "fingerprint") String token, LocalDateTime createdAt) {
    }

    record Renamed(String id, @Encrypted(scope = "test.renamed", name = "id", column = "sealed") String secret) {
    }

    record Plain(String name, int itemCount) {
    }

    record NotText(String id, @Encrypted(scope = "test.bad", name = "id") int count) {
    }

    record NoName(@Encrypted(scope = "test.bad", name = "nobody") String value) {
    }

    record BoundToSealed(String id, @Encrypted(scope = "test.bad", name = "id") String first, @Encrypted(scope = "test.bad", name = "id", bind = "first") String second) {
    }

    record BoundToMissing(String id, @Encrypted(scope = "test.bad", name = "id", bind = "nothing") String value) {
    }

    record BoundToScope(String id, String scope, @Encrypted(scope = "test.bad", name = "id", bind = "scope") String value) {
    }

    record BlankScope(String id, @Encrypted(scope = " ", name = "id") String value) {
    }

    private static EncryptedRecords withSealer() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("sealer", new TestSealer());
        return new EncryptedRecords(beans.getBeanProvider(SecretSealer.class));
    }

    private static EncryptedRecords withoutSealer() {
        return new EncryptedRecords(new StaticListableBeanFactory().getBeanProvider(SecretSealer.class));
    }

    @Test
    void e1TheColumnOfAnEncryptedComponentHoldsNoPlainText() {
        EncryptedRecords records = withSealer();
        Map<String, Object> columns = records.columns(new Credential("a", "fp1", "sk-plain-secret", AT));
        assertEquals(List.of("id", "fingerprint", "token", "created_at"), new ArrayList<>(columns.keySet()));
        assertEquals("a", columns.get("id"));
        assertEquals("fp1", columns.get("fingerprint"));
        assertEquals(AT, columns.get("created_at"));
        String sealed = (String) columns.get("token");
        assertNotEquals("sk-plain-secret", sealed);
        assertFalse(sealed.contains("sk-plain-secret"));
        assertEquals(List.of("id", "sealed"), new ArrayList<>(records.columns(new Renamed("a", "x")).keySet()), "the declared column is the name written");
    }

    @Test
    void e2ASealedValueReadsBackAsTheOriginal() {
        EncryptedRecords records = withSealer();
        Credential original = new Credential("a", "fp1", "sk-plain-secret", AT);
        String sealed = (String) records.columns(original).get("token");
        assertEquals(original, records.open(new Credential("a", "fp1", sealed, AT)));
    }

    @Test
    void e3AValueMovedToAnotherRowOrBoundValueDoesNotOpen() {
        EncryptedRecords records = withSealer();
        String sealed = (String) records.columns(new Credential("a", "fp1", "sk-plain-secret", AT)).get("token");
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> records.open(new Credential("b", "fp1", sealed, AT))).code(), "another name");
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> records.open(new Credential("a", "fp2", sealed, AT))).code(), "another bound value");
        assertEquals("sk-plain-secret", records.open(new Credential("a", "fp1", sealed, AT)).token());
    }

    @Test
    void e4TheSameValueSealsToDifferentText() {
        EncryptedRecords records = withSealer();
        Credential row = new Credential("a", "fp1", "same", AT);
        assertNotEquals(records.columns(row).get("token"), records.columns(row).get("token"));
    }

    @Test
    void e5ANullStaysNullAndARecordWithNothingEncryptedIsReturnedAsItIs() {
        EncryptedRecords records = withSealer();
        assertNull(records.columns(new Credential("a", "fp1", null, AT)).get("token"));
        assertNull(records.open(new Credential("a", "fp1", null, AT)).token());
        assertNull(records.open((Credential) null));
        Plain plain = new Plain("p", 1);
        assertSame(plain, records.open(plain));
        assertEquals(List.of("name", "item_count"), new ArrayList<>(records.columns(plain).keySet()));
    }

    @Test
    void e6AWrongDeclarationFailsAndNamesTheComponent() {
        EncryptedRecords records = withSealer();
        for (Class<?> wrong : List.of(NotText.class, NoName.class, BoundToSealed.class, BoundToMissing.class, BoundToScope.class, BlankScope.class)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> records.check(wrong), wrong.getSimpleName());
            assertTrue(refused.getMessage().contains(wrong.getName()), refused.getMessage());
        }
        assertTrue(assertThrows(IllegalStateException.class, () -> records.check(NotText.class)).getMessage().contains("NotText.count"));
        records.check(Credential.class);
        records.check(Plain.class);
        records.check(String.class);
    }

    @Test
    void e7TheDeclarationsOfAClassAreKeptOncePerClass() {
        EncryptedRecords records = withSealer();
        EncryptedRecords other = withSealer();
        Object first = records.shape(Credential.class);
        String sealed = (String) records.columns(new Credential("a", "fp1", "x", AT)).get("token");
        records.open(new Credential("a", "fp1", sealed, AT));
        assertSame(first, records.shape(Credential.class));
        assertSame(first, other.shape(Credential.class), "the class's declarations do not belong to a codec");
    }

    @Test
    void e8WithoutASealerNothingIsWrittenOrRead() {
        EncryptedRecords records = withoutSealer();
        IllegalStateException written = assertThrows(IllegalStateException.class, () -> records.columns(new Credential("a", "fp1", "sk-plain-secret", AT)));
        assertFalse(written.getMessage().contains("sk-plain-secret"));
        assertThrows(IllegalStateException.class, () -> records.open(new Credential("a", "fp1", "t1.x.y", AT)));
        assertEquals("p", records.open(new Plain("p", 1)).name(), "a row with nothing encrypted needs no sealer");
        assertNull(records.columns(new Credential("a", "fp1", null, AT)).get("token"), "a null needs no sealer either");
    }

    @Test
    void e9ANullBoundComponentIsRefusedWhenAValueIsSealed() {
        EncryptedRecords records = withSealer();
        assertThrows(IllegalArgumentException.class, () -> records.columns(new Credential(null, "fp1", "x", AT)));
        assertThrows(IllegalArgumentException.class, () -> records.columns(new Credential("a", null, "x", AT)));
    }

    @Test
    void e10TheNestedRecordsOfAStoreAreCheckedWhenItStarts() {
        EncryptedRecords records = withSealer();
        ContextLayerStore context = new ContextLayerStore(null, null, null);
        context.encryptedRecords(records);
        context.afterPropertiesSet();
        assertEquals(List.of("name", "fingerprint", "sealed", "stored_at"), new ArrayList<>(records.columns(new ContextLayerStore.JevKeyRow("n", "fp", "k", AT)).keySet()));
        BadEncryptedStore bad = new BadEncryptedStore();
        bad.encryptedRecords(records);
        assertTrue(assertThrows(IllegalStateException.class, bad::afterPropertiesSet).getMessage().contains("BadEncryptedStore$Bad.count"));
    }
}
