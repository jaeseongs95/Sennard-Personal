// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skill providers of an install, re-read for each request that needs them so a new provider needs no server change. One read checks each descriptor against
 * the contract and hashes each schema file a provider names once, however many providers name it; a request that plans, claims and starts reads once and hands the
 * providers on.
 */
@Component
public class SkillRegistry {
    static final Comparator<Object> COLLATION = Collator.getInstance(Locale.ROOT);
    private static final String DESCRIPTOR_CONTRACT = "skill-descriptor.v2.schema.json";

    private final ContentRoot content;
    private final ContractValidator validator;
    private final JsonMapper mapper;

    public SkillRegistry(ContentRoot content, ContractValidator validator, JsonMapper mapper) {
        this.content = content;
        this.validator = validator;
        this.mapper = mapper;
    }

    /** The registry file: the schema version it declares and its descriptors, which stay trees until each is checked against the contract as the file wrote it. */
    private record RegistryFile(String schemaVersion, List<JsonNode> skills) {
    }

    public List<SkillProvider> read() {
        Path file = content.registryFile();
        RegistryFile raw;
        try {
            raw = mapper.readValue(file, RegistryFile.class);
        } catch (JacksonException cause) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.readSkillRegistry"), details("registryPath", file.toString(), "cause", cause.getMessage()), cause);
        }
        if (!"2.0.0".equals(raw.schemaVersion()) || raw.skills() == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.registryV2ObjectSkillsArray"));
        }
        Map<String, String> schemaDigests = new HashMap<>();
        Set<String> skillIds = new HashSet<>();
        List<SkillProvider> providers = new ArrayList<>();
        for (JsonNode entry : raw.skills()) {
            SkillDescriptor descriptor = validator.read(DESCRIPTOR_CONTRACT, entry, SkillDescriptor.class);
            assertDescriptorPath(descriptor);
            if (!skillIds.add(descriptor.skillId())) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.registryContainsDuplicateSkillIdValues"), details("skillId", descriptor.skillId()));
            }
            int index = 0;
            for (SkillDescriptor.Provider provider : descriptor.providers()) {
                index++;
                providers.add(route(descriptor, provider, index, schemaDigests));
            }
        }
        assertProviderConflicts(providers);
        return providers;
    }

    /** The IDs of the skills these providers belong to. */
    public static Set<String> skillIds(List<SkillProvider> providers) {
        Set<String> ids = new HashSet<>();
        providers.forEach(provider -> ids.add(provider.skillId()));
        return ids;
    }

    /**
     * The skills a start may name: those with a provider in the registry, and every other skill the install holds (a folder of {@code skills/} with its {@code SKILL.md}). A skill
     * with no stage to supply (session-board, orchestrator) has no registry entry, yet it is an installed skill that {@code get_skill_inventory} lists, so a start naming only it is
     * a start that names a skill. Read before the start's transaction with the registry (it reads the folder), and handed on with the providers.
     */
    public Set<String> installedSkillIds(List<SkillProvider> providers) {
        Set<String> ids = skillIds(providers);
        Path skills = content.path().resolve("skills");
        try (Stream<Path> children = Files.list(skills)) {
            children.filter(folder -> Files.isRegularFile(folder.resolve("SKILL.md"))).forEach(folder -> ids.add(folder.getFileName().toString()));
        } catch (IOException cause) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.readSkillRegistry"), details("registryPath", skills.toString(), "cause", cause.getMessage()), cause);
        }
        return ids;
    }

    /** Highest priority, then provider key: the same provider for the same capability on every read. */
    public static Optional<SkillProvider> selectByCapability(List<SkillProvider> providers, String capability) {
        return providers.stream()
                .filter(provider -> provider.enabled() && provider.capabilities().contains(capability))
                .min(Comparator.<SkillProvider>comparingLong(provider -> -provider.priority()).thenComparing(SkillProvider::providerKey, COLLATION::compare));
    }

    private SkillProvider route(SkillDescriptor descriptor, SkillDescriptor.Provider provider, int index, Map<String, String> schemaDigests) {
        String skillId = descriptor.skillId();
        return new SkillProvider(skillId, descriptor.version(), descriptor.path(), descriptor.enabled(), descriptor.priority(), skillId + ":" + provider.phase() + ":" + index, provider.capabilities(),
                provider.executionClass(), provider.phase(), provider.phaseOrder(), provider.requiredInputArtifacts(), provider.inputBindings(), provider.producedArtifacts(),
                reference(provider.outputSchema(), schemaDigests), reference(provider.resultSchema(), schemaDigests), provider.stateMapping(), provider.selectionCriteria(),
                routeGate(provider.gate(), schemaDigests), provider.receiptPolicy(), provider.stageValidator());
    }

    private void assertDescriptorPath(SkillDescriptor descriptor) {
        String skillId = descriptor.skillId();
        if (!Objects.equals("./" + skillId, descriptor.path())) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.registryPathMatchSkillId"), details("skillId", skillId, "path", descriptor.path()));
        }
        Path root = content.path();
        Path skillRoot = root.resolve("skills").resolve(skillId).normalize();
        for (SkillDescriptor.Provider provider : descriptor.providers()) {
            Set<String> bindings = new HashSet<>();
            provider.inputBindings().forEach(binding -> bindings.add(binding.targetArtifact()));
            for (String artifact : provider.requiredInputArtifacts()) {
                if (!bindings.contains(artifact)) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.everyRequiredInputArtifactNeeds"), details("skillId", skillId, "phase", provider.phase(), "artifact", artifact));
                }
            }
            List<String> paths = new ArrayList<>(List.of(provider.outputSchema(), provider.resultSchema()));
            String gateValidator = provider.gate().validator();
            if (isSchemaFile(gateValidator)) {
                paths.add(gateValidator);
            }
            for (String schemaPath : paths) {
                Path resolved = root.resolve(schemaPath).normalize();
                if (!resolved.startsWith(root)) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.providerSchemaPathEscapesPlugin"), details("skillId", skillId, "schemaPath", schemaPath));
                }
                if (schemaPath.startsWith("skills/") && !resolved.startsWith(skillRoot)) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.skillLocalSchemaPathRemain"), details("skillId", skillId, "schemaPath", schemaPath));
                }
            }
        }
    }

    /** A gate validator that is a schema file, not the name of a check. */
    private static boolean isSchemaFile(String validator) {
        return validator != null && validator.endsWith(".schema.json");
    }

    /** The schema file at this path in the content root, with the digest of its bytes; a file that several providers name is read once per registry read. */
    private WorkflowPlan.SchemaReference reference(String relativePath, Map<String, String> schemaDigests) {
        String digest = schemaDigests.get(relativePath);
        if (digest == null) {
            try {
                digest = "sha256:" + Digests.sha256Hex(Files.readAllBytes(content.path().resolve(relativePath)));
            } catch (IOException cause) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.readProviderSchema"), details("schemaPath", relativePath, "cause", cause.getMessage()), cause);
            }
            schemaDigests.put(relativePath, digest);
        }
        return new WorkflowPlan.SchemaReference(relativePath, digest);
    }

    private WorkflowPlan.Gate routeGate(SkillDescriptor.Gate gate, Map<String, String> schemaDigests) {
        return new WorkflowPlan.Gate(gate.kind(), gate.policy(), gate.validator(), isSchemaFile(gate.validator()) ? reference(gate.validator(), schemaDigests) : null);
    }

    private static void assertProviderConflicts(List<SkillProvider> providers) {
        Map<String, List<SkillProvider>> owners = new LinkedHashMap<>();
        for (SkillProvider provider : providers) {
            if (provider.enabled()) {
                provider.capabilities().forEach(capability -> owners.computeIfAbsent(capability, ignored -> new ArrayList<>()).add(provider));
            }
        }
        owners.forEach((capability, candidates) -> {
            if (candidates.size() < 2) {
                return;
            }
            long priorities = candidates.stream().map(SkillProvider::priority).distinct().count();
            if (priorities != candidates.size() || candidates.stream().anyMatch(candidate -> candidate.selectionCriteria().isEmpty())) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.registry.overlappingCapabilityProvidersRequireUnique"),
                        details("capability", capability, "providers", candidates.stream().map(SkillProvider::providerKey).toList()));
            }
        });
    }
}
