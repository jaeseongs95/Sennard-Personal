// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import io.github.jaeseongs95.sennard.service.store.Encrypted;

/**
 * The one place a row record's {@link Encrypted} components are sealed and opened: the store layer's counterpart of a JPA {@code AttributeConverter}. {@code ModuleStore} calls it from its
 * common row path (reading: {@link #open}; writing: {@link #columns}), so a repository declares the column once, on the record, and a service never touches the sealer.
 *
 * <p>Which components of a class are encrypted, with the accessors and the constructor it needs, is worked out once per class ({@link ClassValue}) and kept; a row costs no reflection lookup. The same work is
 * the check of the declarations ({@link #check}), which {@code ModuleStore} runs for the records of a store when the store starts, so a misdeclared record stops the start.
 *
 * <p>The sealer's data for a value is what the annotation names and nothing else: the constant {@code scope} and the values of the bound components under their component names; the sealer's
 * name is the value of the {@link Encrypted#name()} component. That is the data a hand-written call with the same scope, name and bound values would give, so a value stored before the
 * annotation existed opens through it.
 *
 * <p>The plain value is a {@code String} (UTF-8 bytes when sealed). The sealer is looked up when a value is sealed or opened: an application without one fails there, with the clear
 * value never written in its place. An encrypted column cannot be searched by equality or ordered by its value ({@link Encrypted}).
 */
@Component
public final class EncryptedRecords {
    /** The entry of the sealer's data that the annotation's {@code scope} takes; a bound component cannot have this name. */
    private static final String SCOPE_ENTRY = "scope";
    private static final Set<String> RESERVED_DATA = Set.of(SCOPE_ENTRY);
    private static final ClassValue<Shape> SHAPES = new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> type) {
            return Shape.of(type);
        }
    };

    private final ObjectProvider<SecretSealer> sealers;

    /** @param sealers the edition's {@link SecretSealer}; looked up at the first value, so a context that never stores an encrypted value needs none */
    public EncryptedRecords(ObjectProvider<SecretSealer> sealers) {
        this.sealers = sealers;
    }

    /** Works out and keeps the declarations of this class, and refuses a wrong one with an {@link IllegalStateException}. A class that is no record or has no encrypted component is accepted. */
    public void check(Class<?> type) {
        SHAPES.get(type);
    }

    /** The kept declarations of the class (the same object on every call). */
    Shape shape(Class<?> type) {
        return SHAPES.get(type);
    }

    /** The row as read from the database: its encrypted components opened. A row with none (or no row) is returned as it is. */
    @SuppressWarnings("unchecked") // the record built is of the row's own class
    public <T> T open(T row) {
        if (row == null) {
            return null;
        }
        Shape shape = SHAPES.get(row.getClass());
        if (!shape.encrypted()) {
            return row;
        }
        Object[] values = shape.read(row);
        transform(shape, values, false);
        return (T) shape.create(values);
    }

    /** The row's columns by name, in the record's order, as they are written: the encrypted components sealed, a {@code null} left {@code null}. */
    public Map<String, Object> columns(Object row) {
        Shape shape = SHAPES.get(row.getClass());
        if (!row.getClass().isRecord()) {
            throw new IllegalArgumentException(row.getClass().getName() + " is not a record: a row is written from a record.");
        }
        Object[] values = shape.read(row);
        transform(shape, values, true);
        Map<String, Object> columns = new LinkedHashMap<>();
        for (Part part : shape.parts()) {
            columns.put(part.column(), values[part.index()]);
        }
        return columns;
    }

    private void transform(Shape shape, Object[] values, boolean sealing) {
        for (Part part : shape.parts()) {
            if (part.encrypted() == null || values[part.index()] == null) {
                continue;
            }
            SecretName name = new SecretName(boundText(shape, part.nameIndex(), values));
            Map<String, String> data = data(shape, part, values);
            String text = (String) values[part.index()];
            values[part.index()] = sealing ? seal(text, name, data) : open(text, name, data);
        }
    }

    private String seal(String plain, SecretName name, Map<String, String> data) {
        byte[] plaintext = plain.getBytes(StandardCharsets.UTF_8);
        try {
            return sealer().seal(name, plaintext, data).text();
        } finally {
            SecretValue.clear(plaintext);
        }
    }

    private String open(String stored, SecretName name, Map<String, String> data) {
        byte[] opened = sealer().open(new SealedValue(stored), name, data);
        try {
            return new String(opened, StandardCharsets.UTF_8);
        } finally {
            SecretValue.clear(opened);
        }
    }

    private SecretSealer sealer() {
        SecretSealer sealer = sealers.getIfAvailable();
        if (sealer == null) {
            throw new IllegalStateException("A record with an @Encrypted component is read or written and the application has no SecretSealer; the value is not stored in the clear instead.");
        }
        return sealer;
    }

    private static Map<String, String> data(Shape shape, Part part, Object[] values) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(SCOPE_ENTRY, part.encrypted().scope());
        for (int index : part.bindIndexes()) {
            data.put(shape.parts().get(index).name(), boundText(shape, index, values));
        }
        return data;
    }

    private static String boundText(Shape shape, int index, Object[] values) {
        Object value = values[index];
        if (value == null) {
            throw new IllegalArgumentException("The component " + shape.parts().get(index).name() + " of " + shape.type().getName() + " is null, and an encrypted value is bound to it.");
        }
        return value.toString();
    }

    /** One component of a record: its column and accessor and, when encrypted, the declaration with the indexes of the components it is bound to. */
    record Part(int index, String name, String column, Method accessor, Encrypted encrypted, int nameIndex, int[] bindIndexes) {
    }

    /** What is known of a class: its components in order and the canonical constructor (none for a class that is no record). */
    record Shape(Class<?> type, List<Part> parts, Constructor<?> constructor, boolean encrypted) {
        static Shape of(Class<?> type) {
            if (!type.isRecord()) {
                return new Shape(type, List.of(), null, false);
            }
            RecordComponent[] components = type.getRecordComponents();
            Map<String, Integer> indexes = new HashMap<>();
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                indexes.put(components[i].getName(), i);
                types[i] = components[i].getType();
            }
            List<Part> parts = new ArrayList<>();
            boolean encrypted = false;
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                Encrypted declared = component.getAnnotation(Encrypted.class);
                if (declared == null) {
                    parts.add(new Part(i, component.getName(), snake(component.getName()), accessor, null, -1, new int[0]));
                    continue;
                }
                encrypted = true;
                if (component.getType() != String.class) {
                    throw refused(type, component, "its type is " + component.getType().getName() + ", and only String can be encrypted");
                }
                if (declared.scope().isBlank()) {
                    throw refused(type, component, "its scope is blank");
                }
                int nameIndex = boundIndex(type, component, components, indexes, declared.name());
                int[] bindIndexes = new int[declared.bind().length];
                for (int b = 0; b < bindIndexes.length; b++) {
                    if (RESERVED_DATA.contains(declared.bind()[b])) {
                        throw refused(type, component, "a bound component cannot be named scope (the data's own entry)");
                    }
                    bindIndexes[b] = boundIndex(type, component, components, indexes, declared.bind()[b]);
                }
                String column = declared.column().isBlank() ? snake(component.getName()) : declared.column();
                parts.add(new Part(i, component.getName(), column, accessor, declared, nameIndex, bindIndexes));
            }
            Constructor<?> constructor;
            try {
                constructor = type.getDeclaredConstructor(types);
                constructor.setAccessible(true);
            } catch (NoSuchMethodException cause) {
                throw new IllegalStateException("The record " + type.getName() + " has no canonical constructor.", cause);
            }
            return new Shape(type, List.copyOf(parts), constructor, encrypted);
        }

        private static int boundIndex(Class<?> type, RecordComponent component, RecordComponent[] components, Map<String, Integer> indexes, String bound) {
            Integer index = indexes.get(bound);
            if (index == null) {
                throw refused(type, component, "it names the component " + bound + ", which the record does not have");
            }
            if (components[index].isAnnotationPresent(Encrypted.class)) {
                throw refused(type, component, "it is bound to the component " + bound + ", which is itself encrypted");
            }
            return index;
        }

        private static IllegalStateException refused(Class<?> type, RecordComponent component, String reason) {
            return new IllegalStateException("@Encrypted on " + type.getName() + "." + component.getName() + " is refused: " + reason + ".");
        }

        /** The component values of the row, in order. */
        Object[] read(Object row) {
            Object[] values = new Object[parts.size()];
            for (Part part : parts) {
                try {
                    values[part.index()] = part.accessor().invoke(row);
                } catch (IllegalAccessException | InvocationTargetException cause) {
                    throw new IllegalStateException("The component " + part.name() + " of " + type.getName() + " cannot be read.", cause);
                }
            }
            return values;
        }

        Object create(Object[] values) {
            try {
                return constructor.newInstance(values);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException cause) {
                throw new IllegalStateException("The record " + type.getName() + " cannot be built.", cause);
            }
        }
    }

    /** {@code storedAt} is {@code stored_at}: the column a component is written to when it names none. */
    static String snake(String name) {
        StringBuilder column = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                column.append('_').append(Character.toLowerCase(c));
            } else {
                column.append(c);
            }
        }
        return column.toString();
    }
}
