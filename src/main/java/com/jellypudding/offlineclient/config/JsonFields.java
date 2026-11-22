package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

// Each reader answers null for a value that is missing or of another kind and never throws.
final class JsonFields {

    private JsonFields() {
    }

    static String text(JsonObject object, String key) {
        JsonPrimitive value = primitive(object.get(key));
        return value == null ? null : value.getAsString();
    }

    // A fraction loses what follows the point.
    static Integer whole(JsonObject object, String key) {
        return whole(object.get(key));
    }

    static Integer whole(JsonElement value) {
        Double number = number(value);
        return number == null || Math.abs(number) > Integer.MAX_VALUE ? null : number.intValue();
    }

    static Double number(JsonObject object, String key) {
        return number(object.get(key));
    }

    static Boolean flag(JsonObject object, String key) {
        JsonPrimitive value = primitive(object.get(key));
        return value == null || !value.isBoolean() ? null : value.getAsBoolean();
    }

    static <E extends Enum<E>> E choice(JsonObject object, String key, Class<E> type) {
        JsonPrimitive value = primitive(object.get(key));
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Double number(JsonElement value) {
        JsonPrimitive primitive = primitive(value);
        if (primitive == null || !primitive.isNumber()) {
            return null;
        }
        double number = primitive.getAsDouble();
        return Double.isFinite(number) ? number : null;
    }

    private static JsonPrimitive primitive(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
    }
}
