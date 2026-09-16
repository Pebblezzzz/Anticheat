package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

final class Phase5CaptureEncoding {
    private Phase5CaptureEncoding() {}

    static String modifiers(EntityAttributeInstance instance) {
        return instance.getModifiers().stream()
                .map(Phase5CaptureEncoding::modifier)
                .collect(Collectors.joining(";"));
    }

    private static String modifier(EntityAttributeModifier modifier) {
        return escape(modifier.getId().toString()) + ":"
                + Double.toString(modifier.getValue()) + ":"
                + operation(modifier.getOperation());
    }

    private static String operation(EntityAttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD_VALUE -> "ADD_VALUE";
            case ADD_MULTIPLIED_BASE -> "ADD_MULTIPLIED_BASE";
            case ADD_MULTIPLIED_TOTAL -> "ADD_MULTIPLIED_TOTAL";
        };
    }

    static String worldIdentity(MinecraftClient client) {
        if (client.world == null || client.world.getRegistryKey() == null) {
            return "unknown";
        }
        return escape(client.world.getRegistryKey().getValue().toString());
    }

    static String escape(String value) {
        Objects.requireNonNull(value);
        return value.replace("%", "%25")
                .replace("\t", "%09")
                .replace("\n", "%0A")
                .replace("\r", "%0D");
    }

    static String unescape(String value) {
        return value.replace("%0D", "\r")
                .replace("%0A", "\n")
                .replace("%09", "\t")
                .replace("%25", "%");
    }
}
