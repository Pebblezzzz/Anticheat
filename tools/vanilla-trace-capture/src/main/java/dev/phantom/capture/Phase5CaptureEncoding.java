package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;

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
        return escape(modifier.id().toString()) + ":"
                + Double.toString(modifier.value()) + ":"
                + modifier.operation().name();
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
