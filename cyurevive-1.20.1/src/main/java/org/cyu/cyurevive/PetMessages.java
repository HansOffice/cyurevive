package org.cyu.cyurevive;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PetMessages {
    private static final Map<String, String> DEFAULTS = loadDefaults();

    private PetMessages() { }

    public static MutableComponent text(String key, Object... arguments) {
        return Component.translatableWithFallback(key, Objects.requireNonNull(DEFAULTS.get(key), key), arguments);
    }

    private static Map<String, String> loadDefaults() {
        var stream = Objects.requireNonNull(PetMessages.class.getResourceAsStream("/assets/cyurevive/lang/en_us.json"));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, String> defaults = new HashMap<>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet()
                .forEach(entry -> defaults.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(defaults);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read pet translations", failure);
        }
    }
}
