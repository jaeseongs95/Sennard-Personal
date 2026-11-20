// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The rules that read the Gradle build scripts (rule h). A Spring Boot release is a curated set of dependency versions (Spring Boot reference,
 * Build Systems, Dependency Management: "you do not need to provide a version"), so a library that the Boot BOM, or an ecosystem BOM that
 * Boot's does not include, already manages gets no version of its own.
 */
final class BuildScriptRules {
    private BuildScriptRules() {
    }

    /**
     * The libraries a BOM manages. {@code artifacts} are the group:artifact entries of the Boot BOM's own pom; {@code groups} are the groups of the
     * BOMs it imports (Jackson, JUnit, Netty, ...), read as group names because the imported poms are not opened here, and the groups of
     * ecosystem BOMs that Boot's does not include (Spring AI has its own).
     */
    record Managed(Set<String> artifacts, Set<String> groups) {
        boolean manages(String group, String artifact) {
            return artifacts.contains(group + ":" + artifact) || groups.stream().anyMatch(managedGroup -> group.equals(managedGroup) || group.startsWith(managedGroup + "."));
        }
    }

    /** Groups whose library family has its own BOM, applied as a platform next to Boot's. */
    static final Set<String> ECOSYSTEM_BOM_GROUPS = Set.of("org.springframework.ai");

    static Managed managed(Path bootBomPom) {
        Set<String> artifacts = new HashSet<>();
        Set<String> groups = new HashSet<>(ECOSYSTEM_BOM_GROUPS);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(bootBomPom.toFile());
            NodeList entries = document.getElementsByTagName("dependency");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String group = text(entry, "groupId");
                String artifact = text(entry, "artifactId");
                if (group.isEmpty() || artifact.isEmpty() || group.contains("$")) {
                    continue;
                }
                if ("import".equals(text(entry, "scope"))) {
                    groups.add(group);
                } else {
                    artifacts.add(group + ":" + artifact);
                }
            }
        } catch (IOException | SAXException | ParserConfigurationException cause) {
            throw new IllegalStateException("cannot read the Boot BOM " + bootBomPom, cause);
        }
        if (artifacts.size() < 100) {
            throw new IllegalStateException("the Boot BOM lists only " + artifacts.size() + " libraries; the pom was not read as expected: " + bootBomPom);
        }
        return new Managed(artifacts, groups);
    }

    private static String text(Element parent, String child) {
        NodeList nodes = parent.getElementsByTagName(child);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static final Pattern VERSIONED = Pattern.compile("\"([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([^\"@:\\s]+)\"");

    /** h: a dependency written with a version although a BOM manages it. A line that applies a BOM itself ({@code platform(...)}) is not a dependency. */
    static List<Violation> managedVersions(String rule, String file, String script, Managed managed) {
        List<Violation> found = new ArrayList<>();
        List<String> lines = script.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("platform(") || line.strip().startsWith("//")) {
                continue;
            }
            Matcher matcher = VERSIONED.matcher(line);
            while (matcher.find()) {
                if (managed.manages(matcher.group(1), matcher.group(2))) {
                    found.add(new Violation(rule, file, i + 1, line.strip() + "   [" + matcher.group(1) + ":" + matcher.group(2) + " is managed by a BOM]"));
                }
            }
        }
        return found;
    }

    /** The Boot version written as {@code val springBootVersion = "x"} in the root script, or null. */
    static String bootVersion(String rootScript) {
        Matcher matcher = Pattern.compile("val springBootVersion = \"([^\"]+)\"").matcher(rootScript);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** h: the Boot version written out again anywhere but its one definition. */
    static List<Violation> bootVersionCopies(String rule, String file, String script, String bootVersion) {
        List<Violation> found = new ArrayList<>();
        List<String> lines = script.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("val springBootVersion =") || line.strip().startsWith("//")) {
                continue;
            }
            if (line.contains("\"" + bootVersion + "\"")) {
                found.add(new Violation(rule, file, i + 1, line.strip() + "   [the Boot version written a second time]"));
            }
        }
        return found;
    }
}
