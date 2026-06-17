package com.nyx.eforms;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EFormSchema {
    private EFormSchema() {
    }

    public static void validateFieldDefinitions(List<FieldDefinition> fields) {
        if (fields.isEmpty()) {
            sneakyThrow(nyxException("Field definitions must not be empty"));
        }

        Map<String, Long> counts = fields.stream()
            .map(FieldDefinition::name)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<String> duplicates = counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();
        if (!duplicates.isEmpty()) {
            sneakyThrow(nyxException("Duplicate field names: " + String.join(", ", duplicates)));
        }

        for (FieldDefinition field : fields) {
            if (field.name().isBlank()) {
                sneakyThrow(nyxException("Field name must not be blank"));
            }
            if ((field.type() == FieldType.SELECT || field.type() == FieldType.MULTI_SELECT)
                && (field.options() == null || field.options().isEmpty())) {
                sneakyThrow(nyxException(
                    "Field '" + field.name() + "' of type " + field.type() + " requires non-empty options list"
                ));
            }
        }
    }

    private static NyxException nyxException(String message) {
        return new NyxException(ErrorCode.VALIDATION_ERROR, message, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
