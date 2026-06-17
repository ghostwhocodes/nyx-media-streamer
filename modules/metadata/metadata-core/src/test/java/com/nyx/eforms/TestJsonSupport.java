package com.nyx.eforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nyx.json.NyxJson;
import java.util.List;
import java.util.Map;

final class TestJsonSupport {
    private static final ObjectMapper JSON = NyxJson.newMapper();

    private TestJsonSupport() {
    }

    static JsonNode jsonPrimitive(String value) {
        return TextNode.valueOf(value);
    }

    static JsonNode jsonPrimitive(int value) {
        return JSON.getNodeFactory().numberNode(value);
    }

    static JsonNode jsonPrimitive(long value) {
        return JSON.getNodeFactory().numberNode(value);
    }

    static JsonNode jsonPrimitive(double value) {
        return JSON.getNodeFactory().numberNode(value);
    }

    static JsonNode jsonPrimitive(boolean value) {
        return JSON.getNodeFactory().booleanNode(value);
    }

    static ArrayNode jsonArray(List<? extends JsonNode> values) {
        ArrayNode array = JSON.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    static ObjectNode jsonObject(Map<String, ? extends JsonNode> values) {
        ObjectNode object = JSON.createObjectNode();
        values.forEach(object::set);
        return object;
    }
}
