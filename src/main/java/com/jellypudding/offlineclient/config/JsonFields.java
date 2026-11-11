package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

// Reads one value of a saved file. Each reader answers null for a value that is missing or of
// another kind and never throws. The caller decides what null stands for.
final class JsonFields {

    private JsonFields() {
    }

    // The text saved under the key. Null when the key is missing or holds no plain value.
    static String text(JsonObject object, String key) {
        JsonPrimitive value = primitive(object.get(key));
        return value == null ? null : value.getAsString();
    }

    // A whole number saved under the key. Null for anything else and for one no int can hold. A
    // fraction loses what follows the point.
    static Integer whole(JsonObject object, String key) {
        return whole(object.get(key));
    }

    // The same for one value of a list.
    static Integer whole(JsonElement value) {
        Double number = number(value);
        return number == null || Math.abs(number) > Integer.MAX_VALUE ? null : number.intValue();
    }

    // Null for a key that is missing or holds anything but a number that can be counted with.
    static Double number(JsonObject object, String key) {
        return number(object.get(key));
    }

    static Boolean flag(JsonObject object, String key) {
        JsonPrimitive value = primitive(object.get(key));
        return value == null || !value.isBoolean() ? null : value.getAsBoolean();
    }

    // Null for a key that is missing or names no constant.
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
