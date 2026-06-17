package com.nyx.eforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.FormVersion;
import com.nyx.eforms.model.MediaMetadata;
import com.nyx.json.NyxJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ExportImportService {
    private final EFormService eformService;
    private final ObjectMapper mapper = NyxJson.newMapper();

    public ExportImportService(EFormService eformService) {
        this.eformService = eformService;
    }

    public byte[] export() {
        List<FormDefinition> forms = eformService.listForms();
        List<FormDefinition> fullForms = forms.stream()
            .map(form -> eformService.getForm(form.id()))
            .filter(form -> form != null)
            .toList();

        Map<String, List<MediaMetadata>> allMetadata = new HashMap<>();
        for (FormDefinition form : fullForms) {
            for (MediaMetadata metadata : eformService.getMetadataByFormId(form.id())) {
                allMetadata.computeIfAbsent(metadata.mediaPath(), ignored -> new ArrayList<>()).add(metadata);
            }
        }

        ExportManifest manifest = new ExportManifest(
            Instant.now(Clock.systemUTC()),
            fullForms.size(),
            allMetadata.values().stream().mapToInt(List::size).sum()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("export/manifest.json"));
            zip.write(mapper.writeValueAsBytes(manifest));
            zip.closeEntry();

            for (FormDefinition form : fullForms) {
                String safeName = form.name().replaceAll("[^a-zA-Z0-9_-]", "_");
                zip.putNextEntry(new ZipEntry("export/forms/" + safeName + ".json"));
                zip.write(mapper.writeValueAsBytes(form));
                zip.closeEntry();
            }

            for (Map.Entry<String, List<MediaMetadata>> entry : allMetadata.entrySet()) {
                ExportedMetadata exported = new ExportedMetadata(
                    entry.getKey(),
                    entry.getValue().stream()
                        .map(metadata -> new ExportedMetadataEntry(
                            metadata.formId(),
                            metadata.formVersion(),
                            metadata.values(),
                            metadata.contentHash(),
                            metadata.createdAt(),
                            metadata.updatedAt()
                        ))
                        .toList()
                );
                String safePath = entry.getKey().replaceFirst("^/+", "").replace('/', '_');
                zip.putNextEntry(new ZipEntry("export/metadata/" + safePath + ".json"));
                zip.write(mapper.writeValueAsBytes(exported));
                zip.closeEntry();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to export eforms archive", exception);
        }

        return output.toByteArray();
    }

    public ImportResult importArchive(byte[] zipBytes) {
        return importArchive(zipBytes, null);
    }

    public ImportResult importArchive(byte[] zipBytes, Set<String> formNames) {
        return importData(zipBytes, formNames);
    }

    public ImportResult importData(byte[] zipBytes) {
        return importData(zipBytes, null);
    }

    public ImportResult importData(byte[] zipBytes, Set<String> formNames) {
        Map<String, FormDefinition> forms = new HashMap<>();
        List<ExportedMetadata> metadataEntries = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                byte[] content = zip.readAllBytes();
                String name = entry.getName();
                if (name.endsWith("manifest.json")) {
                    mapper.readValue(content, ExportManifest.class);
                } else if (name.contains("/forms/") && name.endsWith(".json")) {
                    FormDefinition form = mapper.readValue(content, FormDefinition.class);
                    forms.put(form.name(), form);
                } else if (name.contains("/metadata/") && name.endsWith(".json")) {
                    metadataEntries.add(mapper.readValue(content, ExportedMetadata.class));
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to import eforms archive", exception);
        }

        int formsCreated = 0;
        int formsSkipped = 0;
        int metadataCreated = 0;
        int metadataUpdated = 0;
        int metadataSkipped = 0;
        List<String> errors = new ArrayList<>();
        Map<String, String> formIdMapping = new HashMap<>();

        List<FormDefinition> existingForms = eformService.listForms();
        for (Map.Entry<String, FormDefinition> entry : forms.entrySet()) {
            String name = entry.getKey();
            FormDefinition form = entry.getValue();
            if (formNames != null && !formNames.contains(name)) {
                formsSkipped++;
                continue;
            }

            try {
                FormDefinition existing = existingForms.stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElse(null);
                if (existing != null) {
                    formIdMapping.put(form.id(), existing.id());
                    formsSkipped++;
                    continue;
                }

                FormVersion latestVersion = form.versions().stream()
                    .max(Comparator.comparingInt(FormVersion::version))
                    .orElse(null);
                if (latestVersion == null) {
                    errors.add("Form '" + name + "' has no versions");
                    continue;
                }

                FormDefinition created = eformService.createForm(name, form.mediaTypes(), latestVersion.fields());
                formIdMapping.put(form.id(), created.id());

                form.versions().stream()
                    .sorted(Comparator.comparingInt(FormVersion::version))
                    .filter(version -> version.version() > 1)
                    .forEach(version -> eformService.updateForm(created.id(), version.fields()));
                formsCreated++;
            } catch (Exception exception) {
                errors.add("Failed to import form '" + name + "': " + exception.getMessage());
            }
        }

        for (ExportedMetadata exportedMetadata : metadataEntries) {
            for (ExportedMetadataEntry entry : exportedMetadata.getEntries()) {
                String mappedFormId = formIdMapping.get(entry.getFormId());
                if (mappedFormId == null) {
                    metadataSkipped++;
                    continue;
                }

                try {
                    MediaMetadata existing = eformService.getMetadata(exportedMetadata.getMediaPath()).stream()
                        .filter(metadata -> metadata.formId().equals(mappedFormId))
                        .findFirst()
                        .orElse(null);
                    if (existing != null) {
                        eformService.updateMetadata(existing.id(), entry.getValues());
                        metadataUpdated++;
                    } else {
                        eformService.attachMetadata(exportedMetadata.getMediaPath(), mappedFormId, entry.getValues());
                        metadataCreated++;
                    }
                } catch (Exception exception) {
                    errors.add("Failed to import metadata for '" + exportedMetadata.getMediaPath() + "': " + exception.getMessage());
                }
            }
        }

        return new ImportResult(
            formsCreated,
            formsSkipped,
            metadataCreated,
            metadataUpdated,
            metadataSkipped,
            errors
        );
    }
}
