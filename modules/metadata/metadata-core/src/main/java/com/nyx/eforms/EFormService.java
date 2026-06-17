package com.nyx.eforms;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.AuditLogger;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.FormVersion;
import com.nyx.eforms.model.MediaMetadata;
import com.nyx.eforms.model.MediaType;
import com.nyx.eforms.model.SearchQuery;
import com.nyx.eforms.model.SearchResult;
import com.nyx.eforms.model.SearchResultItem;
import com.nyx.json.NyxJson;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EFormService {
    private static final TypeReference<Set<MediaType>> MEDIA_TYPES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<FieldDefinition>> FIELD_DEFINITIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP_TYPE = new TypeReference<>() {};

    private final Logger log = LoggerFactory.getLogger(EFormService.class);
    private final Jdbi jdbi;
    private final ObjectMapper mapper = NyxJson.newMapper();

    public EFormService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    public FormDefinition createForm(
        String name,
        Set<MediaType> mediaTypes,
        List<FieldDefinition> fields
    ) {
        return dbWriteQuery(handle -> {
            EFormSchema.validateFieldDefinitions(fields);
            log.info("Creating form '{}' with {} fields, media types: {}", name, fields.size(), mediaTypes);

            String formId = UUID.randomUUID().toString();
            String versionId = UUID.randomUUID().toString();
            Instant now = Instant.now(Clock.systemUTC());
            String nowText = now.toString();

            handle.createUpdate(
                """
                INSERT INTO form_definitions (
                    id, name, media_types, current_version, created_at, updated_at
                ) VALUES (
                    :id, :name, :mediaTypes, :currentVersion, :createdAt, :updatedAt
                )
                """
            )
                .bind("id", formId)
                .bind("name", name)
                .bind("mediaTypes", writeMediaTypes(mediaTypes))
                .bind("currentVersion", 1)
                .bind("createdAt", nowText)
                .bind("updatedAt", nowText)
                .execute();

            handle.createUpdate(
                """
                INSERT INTO form_versions (id, form_id, version, fields, created_at)
                VALUES (:id, :formId, :version, :fields, :createdAt)
                """
            )
                .bind("id", versionId)
                .bind("formId", formId)
                .bind("version", 1)
                .bind("fields", writeFields(fields))
                .bind("createdAt", nowText)
                .execute();

            return new FormDefinition(
                formId,
                name,
                1,
                mediaTypes,
                List.of(new FormVersion(1, fields, now)),
                now,
                now
            );
        });
    }

    public FormDefinition getForm(String formId) {
        return dbQuery(handle -> {
            FormRecord record = loadFormRecord(handle, formId);
            if (record == null) {
                return null;
            }
            return toFormDefinition(record, loadFormVersions(handle, formId));
        });
    }

    public List<FormDefinition> listForms() {
        return dbQuery(handle -> handle.createQuery(
            """
            SELECT
                fd.id,
                fd.name,
                fd.media_types,
                fd.current_version,
                fd.created_at,
                fd.updated_at,
                fv.version AS active_version,
                fv.fields AS active_fields,
                fv.created_at AS active_created_at
            FROM form_definitions fd
            LEFT JOIN form_versions fv
                ON fv.form_id = fd.id
               AND fv.version = fd.current_version
            ORDER BY fd.updated_at DESC
            """
        )
            .map((rs, ctx) -> {
                FormRecord form = mapFormRecord(rs);
                Integer activeVersionNumber = rs.getInt("active_version");
                FormVersion activeVersion = rs.wasNull()
                    ? null
                    : new FormVersion(
                        activeVersionNumber,
                        readFields(rs.getString("active_fields")),
                        Instant.parse(rs.getString("active_created_at"))
                    );
                return toFormDefinition(
                    form,
                    activeVersion == null ? List.of() : List.of(activeVersion)
                );
            })
            .list());
    }

    public Map.Entry<FormDefinition, VersionDiff> updateForm(
        String formId,
        List<FieldDefinition> fields
    ) {
        return dbWriteQuery(handle -> {
            EFormSchema.validateFieldDefinitions(fields);
            log.info("Updating form {} with {} fields", formId, fields.size());

            FormRecord form = loadFormRecord(handle, formId);
            if (form == null) {
                return sneakyThrow(nyxException(ErrorCode.FORM_NOT_FOUND, "Form not found: " + formId));
            }

            FormVersion currentVersion = loadFormVersion(handle, formId, form.currentVersion());
            if (currentVersion == null) {
                return sneakyThrow(nyxException(
                    ErrorCode.FORM_NOT_FOUND,
                    "Form version not found: " + formId + "@" + form.currentVersion()
                ));
            }

            int nextVersion = form.currentVersion() + 1;
            VersionDiff diff = SchemaVersioning.diffFields(
                currentVersion.fields(),
                fields,
                form.currentVersion(),
                nextVersion
            );
            Instant now = Instant.now(Clock.systemUTC());
            String nowText = now.toString();

            handle.createUpdate(
                """
                INSERT INTO form_versions (id, form_id, version, fields, created_at)
                VALUES (:id, :formId, :version, :fields, :createdAt)
                """
            )
                .bind("id", UUID.randomUUID().toString())
                .bind("formId", formId)
                .bind("version", nextVersion)
                .bind("fields", writeFields(fields))
                .bind("createdAt", nowText)
                .execute();

            handle.createUpdate(
                """
                UPDATE form_definitions
                SET current_version = :currentVersion, updated_at = :updatedAt
                WHERE id = :id
                """
            )
                .bind("currentVersion", nextVersion)
                .bind("updatedAt", nowText)
                .bind("id", formId)
                .execute();

            FormDefinition updated = new FormDefinition(
                formId,
                form.name(),
                nextVersion,
                readMediaTypes(form.mediaTypes()),
                loadFormVersions(handle, formId),
                Instant.parse(form.createdAt()),
                now
            );
            return Map.entry(updated, diff);
        });
    }

    public void deleteForm(String formId) {
        deleteForm(formId, false);
    }

    public void deleteForm(String formId, boolean deleteMetadata) {
        dbWriteQuery(handle -> {
            log.info("Deleting form {} (deleteMetadata={})", formId, deleteMetadata);
            FormRecord existing = loadFormRecord(handle, formId);
            if (existing == null) {
                sneakyThrow(nyxException(ErrorCode.FORM_NOT_FOUND, "Form not found: " + formId));
            }

            if (deleteMetadata) {
                List<String> metadataIds = handle.createQuery("SELECT id FROM media_metadata WHERE form_id = :formId")
                    .bind("formId", formId)
                    .mapTo(String.class)
                    .list();
                for (String metadataId : metadataIds) {
                    execFtsParam(handle, "DELETE FROM metadata_fts WHERE metadata_id = ?", metadataId);
                }
                handle.createUpdate("DELETE FROM media_metadata WHERE form_id = :formId")
                    .bind("formId", formId)
                    .execute();
            }

            handle.createUpdate("DELETE FROM form_versions WHERE form_id = :formId")
                .bind("formId", formId)
                .execute();
            handle.createUpdate("DELETE FROM form_definitions WHERE id = :formId")
                .bind("formId", formId)
                .execute();
            AuditLogger.log("DELETE", "/api/v1/forms/" + formId, "service", formId, "200");
            return null;
        });
    }

    public MediaMetadata attachMetadata(
        String mediaPath,
        String formId,
        Map<String, JsonNode> values
    ) {
        return dbWriteQuery(handle -> {
            FormRecord form = loadFormRecord(handle, formId);
            if (form == null) {
                return sneakyThrow(nyxException(ErrorCode.FORM_NOT_FOUND, "Form not found: " + formId));
            }

            FormVersion version = loadFormVersion(handle, formId, form.currentVersion());
            if (version == null) {
                return sneakyThrow(nyxException(ErrorCode.FORM_NOT_FOUND, "Form version not found"));
            }

            validateMetadataValues(version.fields(), values);

            String metadataId = UUID.randomUUID().toString();
            Instant now = Instant.now(Clock.systemUTC());
            String nowText = now.toString();

            handle.createUpdate(
                """
                INSERT INTO media_metadata (
                    id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
                ) VALUES (
                    :id, :mediaPath, :contentHash, :formId, :formVersion, :values, :createdAt, :updatedAt
                )
                """
            )
                .bind("id", metadataId)
                .bind("mediaPath", mediaPath)
                .bindNull("contentHash", Types.VARCHAR)
                .bind("formId", formId)
                .bind("formVersion", form.currentVersion())
                .bind("values", writeValues(values))
                .bind("createdAt", nowText)
                .bind("updatedAt", nowText)
                .execute();

            String textContent = buildFtsContent(version.fields(), values);
            if (!textContent.isBlank()) {
                execFtsParam(
                    handle,
                    "INSERT INTO metadata_fts(metadata_id, content) VALUES (?, ?)",
                    metadataId,
                    textContent
                );
            }

            return new MediaMetadata(
                metadataId,
                mediaPath,
                null,
                formId,
                form.currentVersion(),
                values,
                now,
                now
            );
        });
    }

    public MediaMetadata updateMetadata(String metadataId, Map<String, JsonNode> values) {
        return dbWriteQuery(handle -> {
            MetadataRecord metadata = loadMetadataRecord(handle, metadataId);
            if (metadata == null) {
                return sneakyThrow(nyxException(
                    ErrorCode.METADATA_NOT_FOUND,
                    "Metadata not found: " + metadataId
                ));
            }

            FormVersion version = loadFormVersion(handle, metadata.formId(), metadata.formVersion());
            if (version == null) {
                return sneakyThrow(nyxException(ErrorCode.FORM_NOT_FOUND, "Form version not found"));
            }

            validateMetadataValues(version.fields(), values);

            Instant now = Instant.now(Clock.systemUTC());
            handle.createUpdate(
                """
                UPDATE media_metadata
                SET "values" = :values, updated_at = :updatedAt
                WHERE id = :id
                """
            )
                .bind("values", writeValues(values))
                .bind("updatedAt", now.toString())
                .bind("id", metadataId)
                .execute();

            execFtsParam(handle, "DELETE FROM metadata_fts WHERE metadata_id = ?", metadataId);
            String textContent = buildFtsContent(version.fields(), values);
            if (!textContent.isBlank()) {
                execFtsParam(
                    handle,
                    "INSERT INTO metadata_fts(metadata_id, content) VALUES (?, ?)",
                    metadataId,
                    textContent
                );
            }

            MediaMetadata current = toMediaMetadata(metadata);
            return new MediaMetadata(
                current.id(),
                current.mediaPath(),
                current.contentHash(),
                current.formId(),
                current.formVersion(),
                values,
                current.createdAt(),
                now
            );
        });
    }

    public List<MediaMetadata> getMetadata(String mediaPath) {
        return dbQuery(handle -> loadMetadataByPath(handle, mediaPath).stream().map(this::toMediaMetadata).toList());
    }

    public MediaMetadata getMetadataById(String metadataId) {
        return dbQuery(handle -> {
            MetadataRecord record = loadMetadataRecord(handle, metadataId);
            return record == null ? null : toMediaMetadata(record);
        });
    }

    public List<MediaMetadata> getMetadataByFormId(String formId) {
        return dbQuery(handle -> loadMetadataByFormId(handle, formId).stream().map(this::toMediaMetadata).toList());
    }

    public void deleteMetadata(String metadataId) {
        dbWriteQuery(handle -> {
            MetadataRecord existing = loadMetadataRecord(handle, metadataId);
            if (existing == null) {
                sneakyThrow(nyxException(
                    ErrorCode.METADATA_NOT_FOUND,
                    "Metadata not found: " + metadataId
                ));
            }

            execFtsParam(handle, "DELETE FROM metadata_fts WHERE metadata_id = ?", metadataId);
            handle.createUpdate("DELETE FROM media_metadata WHERE id = :id")
                .bind("id", metadataId)
                .execute();
            AuditLogger.log("DELETE", "/api/v1/metadata/" + metadataId, "service", metadataId, "200");
            return null;
        });
    }

    public int storeContentHash(String mediaPath, String hash) {
        return dbWriteQuery(handle -> handle.createUpdate(
            """
            UPDATE media_metadata
            SET content_hash = :contentHash, updated_at = :updatedAt
            WHERE media_path = :mediaPath
            """
        )
            .bind("contentHash", hash)
            .bind("updatedAt", Instant.now(Clock.systemUTC()).toString())
            .bind("mediaPath", mediaPath)
            .execute());
    }

    public SearchResult search(SearchQuery query) {
        return dbQuery(handle -> {
            log.debug(
                "Search query: text='{}', formId={}, mediaType={}",
                query.text(),
                query.formId(),
                query.mediaType()
            );
            String ftsText = query.text() != null && !query.text().isBlank() ? query.text() : null;
            List<String> whereClauses = new ArrayList<>();

            if (query.formId() != null) {
                whereClauses.add("form_id = :formId");
            }

            Set<String> ftsMatchIds;
            if (ftsText != null) {
                ftsMatchIds = new HashSet<>();
                execFtsQuery(
                    handle,
                    "SELECT metadata_id FROM metadata_fts WHERE metadata_fts MATCH ?",
                    resultSet -> {
                        while (resultSet.next()) {
                            ftsMatchIds.add(resultSet.getString("metadata_id"));
                        }
                    },
                    ftsEscape(ftsText)
                );
                if (ftsMatchIds.isEmpty()) {
                    return new SearchResult(List.of(), 0, query.limit(), query.offset());
                }
                whereClauses.add("id IN (<ids>)");
            } else {
                ftsMatchIds = null;
            }

            String whereSql = whereClauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", whereClauses);
            String sortColumn;
            if ("createdAt".equals(query.sortBy())) {
                sortColumn = "created_at";
            } else {
                sortColumn = "updated_at";
            }

            var totalQuery = handle.createQuery("SELECT COUNT(*) FROM media_metadata " + whereSql);
            if (query.formId() != null) {
                totalQuery.bind("formId", query.formId());
            }
            if (ftsMatchIds != null) {
                totalQuery.bindList("ids", ftsMatchIds);
            }
            int total = totalQuery.mapTo(Integer.class).one();

            var rowsQuery = handle.createQuery(
                """
                SELECT id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
                FROM media_metadata
                """
                    + System.lineSeparator()
                    + whereSql
                    + System.lineSeparator()
                    + "ORDER BY "
                    + sortColumn
                    + " DESC"
                    + System.lineSeparator()
                    + "LIMIT :limit OFFSET :offset"
            );
            if (query.formId() != null) {
                rowsQuery.bind("formId", query.formId());
            }
            if (ftsMatchIds != null) {
                rowsQuery.bindList("ids", ftsMatchIds);
            }
            List<MetadataRecord> rows = rowsQuery
                .bind("limit", query.limit())
                .bind("offset", query.offset())
                .map((rs, ctx) -> mapMetadataRecord(rs))
                .list();

            Map<String, Set<MediaType>> formMediaTypes;
            if (query.mediaType() != null && !rows.isEmpty()) {
                Set<String> formIds = rows.stream().map(MetadataRecord::formId).collect(Collectors.toSet());
                formMediaTypes = handle.createQuery("SELECT id, media_types FROM form_definitions WHERE id IN (<ids>)")
                    .bindList("ids", formIds)
                    .map((rs, ctx) -> Map.entry(rs.getString("id"), readMediaTypes(rs.getString("media_types"))))
                    .list()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            } else {
                formMediaTypes = Map.of();
            }

            Map<String, Double> rankMap;
            if (ftsText != null && ftsMatchIds != null) {
                rankMap = new HashMap<>();
                execFtsQuery(
                    handle,
                    "SELECT metadata_id, rank FROM metadata_fts WHERE metadata_fts MATCH ?",
                    resultSet -> {
                        while (resultSet.next()) {
                            rankMap.put(resultSet.getString("metadata_id"), resultSet.getDouble("rank"));
                        }
                    },
                    ftsEscape(ftsText)
                );
            } else {
                rankMap = Map.of();
            }

            List<SearchResultItem> items = rows.stream()
                .map(row -> new SearchResultItem(
                    row.mediaPath(),
                    formMediaTypes.getOrDefault(row.formId(), Set.of()).stream().findFirst().orElse(null),
                    row.formId(),
                    row.formVersion(),
                    readValues(row.values()),
                    rankMap.get(row.id())
                ))
                .toList();

            if (query.mediaType() != null) {
                items = items.stream()
                    .filter(item -> query.mediaType().equals(item.mediaType()))
                    .toList();
            }
            if (!query.filters().isEmpty()) {
                items = items.stream()
                    .filter(item -> query.filters().entrySet().stream().allMatch(filter ->
                        filter.getValue().equals(item.metadata().get(filter.getKey()))
                    ))
                    .toList();
            }

            int totalItems = (!query.filters().isEmpty() || query.mediaType() != null) ? items.size() : total;
            return new SearchResult(items, totalItems, query.limit(), query.offset());
        });
    }

    public void validateMetadataValues(List<FieldDefinition> fields, Map<String, JsonNode> values) {
        Map<String, FieldDefinition> fieldsByName = fields.stream()
            .collect(Collectors.toMap(FieldDefinition::name, field -> field));

        for (String key : values.keySet()) {
            if (!fieldsByName.containsKey(key)) {
                sneakyThrow(nyxException(ErrorCode.VALIDATION_ERROR, "Unknown field: " + key));
            }
        }

        for (FieldDefinition field : fields) {
            if (field.required() && !values.containsKey(field.name())) {
                sneakyThrow(nyxException(
                    ErrorCode.VALIDATION_ERROR,
                    "Required field missing: " + field.name()
                ));
            }
        }

        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            validateFieldValue(fieldsByName.get(entry.getKey()), entry.getValue());
        }
    }

    public String buildFtsContent(List<FieldDefinition> fields, Map<String, JsonNode> values) {
        return fields.stream()
            .filter(field -> field.type() == FieldType.TEXT)
            .map(field -> values.get(field.name()))
            .filter(value -> value != null && value.isTextual())
            .map(JsonNode::textValue)
            .collect(Collectors.joining(" "));
    }

    private void validateFieldValue(FieldDefinition field, JsonNode value) {
        switch (field.type()) {
            case TEXT -> requireNode(value.isTextual(), field, "must be a string");
            case NUMBER -> requireNode(value.isNumber(), field, "must be a number");
            case BOOLEAN -> requireNode(value.isBoolean(), field, "must be a boolean");
            case DATE -> requireNode(value.isTextual(), field, "must be a date string");
            case SELECT -> {
                requireNode(value.isTextual(), field, "must be a string");
                List<String> options = field.options();
                if (options != null && !options.contains(value.textValue())) {
                    sneakyThrow(nyxException(
                        ErrorCode.VALIDATION_ERROR,
                        "Field '" + field.name() + "' value '" + value.textValue() + "' not in options: " + options
                    ));
                }
            }
            case MULTI_SELECT -> {
                requireNode(value.isArray(), field, "must be an array");
                List<String> options = field.options();
                for (JsonNode entry : value) {
                    requireNode(entry.isTextual(), field, "array items must be strings");
                    if (options != null && !options.contains(entry.textValue())) {
                        sneakyThrow(nyxException(
                            ErrorCode.VALIDATION_ERROR,
                            "Field '" + field.name() + "' value '" + entry.textValue() + "' not in options: " + options
                        ));
                    }
                }
            }
        }
    }

    private void requireNode(boolean valid, FieldDefinition field, String description) {
        if (!valid) {
            sneakyThrow(nyxException(
                ErrorCode.VALIDATION_ERROR,
                "Field '" + field.name() + "' " + description
            ));
        }
    }

    private FormDefinition toFormDefinition(FormRecord record, List<FormVersion> versions) {
        return new FormDefinition(
            record.id(),
            record.name(),
            record.currentVersion(),
            readMediaTypes(record.mediaTypes()),
            versions,
            Instant.parse(record.createdAt()),
            Instant.parse(record.updatedAt())
        );
    }

    private MediaMetadata toMediaMetadata(MetadataRecord record) {
        return new MediaMetadata(
            record.id(),
            record.mediaPath(),
            record.contentHash(),
            record.formId(),
            record.formVersion(),
            readValues(record.values()),
            Instant.parse(record.createdAt()),
            Instant.parse(record.updatedAt())
        );
    }

    private FormRecord loadFormRecord(Handle handle, String formId) {
        return handle.createQuery(
            """
            SELECT id, name, media_types, current_version, created_at, updated_at
            FROM form_definitions
            WHERE id = :id
            """
        )
            .bind("id", formId)
            .map((rs, ctx) -> mapFormRecord(rs))
            .findOne()
            .orElse(null);
    }

    private List<FormVersion> loadFormVersions(Handle handle, String formId) {
        return handle.createQuery(
            """
            SELECT version, fields, created_at
            FROM form_versions
            WHERE form_id = :formId
            ORDER BY version ASC
            """
        )
            .bind("formId", formId)
            .map((rs, ctx) -> mapFormVersion(rs))
            .list();
    }

    private FormVersion loadFormVersion(Handle handle, String formId, int version) {
        return handle.createQuery(
            """
            SELECT version, fields, created_at
            FROM form_versions
            WHERE form_id = :formId AND version = :version
            """
        )
            .bind("formId", formId)
            .bind("version", version)
            .map((rs, ctx) -> mapFormVersion(rs))
            .findOne()
            .orElse(null);
    }

    private MetadataRecord loadMetadataRecord(Handle handle, String metadataId) {
        return handle.createQuery(
            """
            SELECT id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
            FROM media_metadata
            WHERE id = :id
            """
        )
            .bind("id", metadataId)
            .map((rs, ctx) -> mapMetadataRecord(rs))
            .findOne()
            .orElse(null);
    }

    private List<MetadataRecord> loadMetadataByPath(Handle handle, String mediaPath) {
        return handle.createQuery(
            """
            SELECT id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
            FROM media_metadata
            WHERE media_path = :mediaPath
            ORDER BY created_at ASC
            """
        )
            .bind("mediaPath", mediaPath)
            .map((rs, ctx) -> mapMetadataRecord(rs))
            .list();
    }

    private List<MetadataRecord> loadMetadataByFormId(Handle handle, String formId) {
        return handle.createQuery(
            """
            SELECT id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
            FROM media_metadata
            WHERE form_id = :formId
            ORDER BY created_at ASC
            """
        )
            .bind("formId", formId)
            .map((rs, ctx) -> mapMetadataRecord(rs))
            .list();
    }

    private FormRecord mapFormRecord(ResultSet resultSet) throws java.sql.SQLException {
        return new FormRecord(
            resultSet.getString("id"),
            resultSet.getString("name"),
            resultSet.getString("media_types"),
            resultSet.getInt("current_version"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private FormVersion mapFormVersion(ResultSet resultSet) throws java.sql.SQLException {
        return new FormVersion(
            resultSet.getInt("version"),
            readFields(resultSet.getString("fields")),
            Instant.parse(resultSet.getString("created_at"))
        );
    }

    private MetadataRecord mapMetadataRecord(ResultSet resultSet) throws java.sql.SQLException {
        return new MetadataRecord(
            resultSet.getString("id"),
            resultSet.getString("media_path"),
            resultSet.getString("content_hash"),
            resultSet.getString("form_id"),
            resultSet.getInt("form_version"),
            resultSet.getString("values"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private String writeMediaTypes(Set<MediaType> mediaTypes) {
        return writeValue(mediaTypes);
    }

    private Set<MediaType> readMediaTypes(String raw) {
        return readValue(raw, MEDIA_TYPES_TYPE);
    }

    private String writeFields(List<FieldDefinition> fields) {
        return writeValue(fields);
    }

    private List<FieldDefinition> readFields(String raw) {
        return readValue(raw, FIELD_DEFINITIONS_TYPE);
    }

    private String writeValues(Map<String, JsonNode> values) {
        return writeValue(values);
    }

    private Map<String, JsonNode> readValues(String raw) {
        return readValue(raw, JSON_NODE_MAP_TYPE);
    }

    private String writeValue(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            return sneakyThrow(new IllegalStateException("Failed to serialize eforms payload", exception));
        }
    }

    private <T> T readValue(String raw, TypeReference<T> type) {
        try {
            return mapper.readValue(raw, type);
        } catch (Exception exception) {
            return sneakyThrow(new IllegalStateException("Failed to deserialize eforms payload", exception));
        }
    }

    private static String ftsEscape(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void execFtsParam(Handle handle, String sql, String... args) {
        try (var statement = handle.getConnection().prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setString(index + 1, args[index]);
            }
            statement.executeUpdate();
        } catch (Exception exception) {
            sneakyThrow(exception);
        }
    }

    private void execFtsQuery(Handle handle, String sql, ResultSetHandler handler, String... args) {
        try (var statement = handle.getConnection().prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setString(index + 1, args[index]);
            }
            try (var resultSet = statement.executeQuery()) {
                handler.accept(resultSet);
            }
        } catch (Exception exception) {
            sneakyThrow(exception);
        }
    }

    private <T> T dbQuery(HandleFunction<T> block) {
        return withHandleUnchecked(jdbi, handle -> {
            try {
                return block.apply(handle);
            } catch (Exception exception) {
                return sneakyThrow(exception);
            }
        });
    }

    private <T> T dbWriteQuery(HandleFunction<T> block) {
        return sqliteWriteTransaction(jdbi, handle -> {
            try {
                return block.apply(handle);
            } catch (Exception exception) {
                return sneakyThrow(exception);
            }
        });
    }

    private static NyxException nyxException(ErrorCode errorCode, String message) {
        return new NyxException(errorCode, message, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    @FunctionalInterface
    private interface HandleFunction<T> {
        T apply(Handle handle) throws Exception;
    }

    @FunctionalInterface
    private interface ResultSetHandler {
        void accept(ResultSet resultSet) throws Exception;
    }

    private record FormRecord(
        String id,
        String name,
        String mediaTypes,
        int currentVersion,
        String createdAt,
        String updatedAt
    ) {
    }

    private record MetadataRecord(
        String id,
        String mediaPath,
        String contentHash,
        String formId,
        int formVersion,
        String values,
        String createdAt,
        String updatedAt
    ) {
    }
}
