package com.itsthatnova.compsnow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * JSON-backed config for resolved snow variant textures.
 */
public final class SnowVariantConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "compsnow_snow_variants.json";
    public static final int VARIANT_SLOTS = 4;

    public enum Mode {
        @SerializedName("Auto")
        AUTO,
        @SerializedName("Manual_Override")
        MANUAL_OVERRIDE;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            String normalized = raw.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if ("MANUAL_OVERRIDE".equals(normalized)) {
                return MANUAL_OVERRIDE;
            }
            return AUTO;
        }

        public String configValue() {
            return this == MANUAL_OVERRIDE ? "Manual_Override" : "Auto";
        }
    }

    private static final class Data {
        String mode = Mode.AUTO.configValue();
        List<String> manualVariants = new ArrayList<>(Arrays.asList("", "", "", ""));
        boolean debugLogging = false;
    }

    private final Mode mode;
    private final List<String> manualVariants;
    private final boolean debugLogging;

    private SnowVariantConfig(Mode mode, List<String> manualVariants, boolean debugLogging) {
        this.mode = mode;
        this.manualVariants = manualVariants;
        this.debugLogging = debugLogging;
    }

    public static SnowVariantConfig loadOrCreate() {
        Path path = getConfigPath();

        if (Files.notExists(path)) {
            SnowVariantConfig defaults = defaults();
            defaults.save();
            LOGGER.warn("Created default snow variant config at {}", path.toAbsolutePath());
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            SnowVariantConfig config = fromData(data);
            config.save(); // normalize/pad/truncate back to disk
            LOGGER.warn("Loaded snow variant config: mode={}, manualVariants={}",
                    config.mode.configValue(), config.manualVariants);
            return config;
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Failed to read snow variant config at {}: {}. Recreating defaults.",
                    path.toAbsolutePath(), e.getMessage());
            SnowVariantConfig defaults = defaults();
            defaults.save();
            return defaults;
        }
    }

    public void save() {
        Path path = getConfigPath();
        Data data = new Data();
        data.mode = mode.configValue();
        data.manualVariants = new ArrayList<>(manualVariants);
        data.debugLogging = debugLogging;

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save snow variant config at {}: {}", path.toAbsolutePath(), e.getMessage());
        }
    }

    private static SnowVariantConfig fromData(Data data) {
        if (data == null) {
            return defaults();
        }

        Mode mode = Mode.parse(data.mode);
        List<String> variants = new ArrayList<>(VARIANT_SLOTS);
        List<String> source = data.manualVariants != null ? data.manualVariants : List.of();

        for (int i = 0; i < VARIANT_SLOTS; i++) {
            String value = i < source.size() && source.get(i) != null ? source.get(i).trim() : "";
            variants.add(value);
        }

        return new SnowVariantConfig(mode, variants, data.debugLogging);
    }

    public static SnowVariantConfig defaults() {
        return new SnowVariantConfig(Mode.AUTO, new ArrayList<>(Arrays.asList("", "", "", "")), false);
    }

    public static Path getConfigPath() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path baseDir = client != null ? client.runDirectory.toPath() : Path.of(".");
        return baseDir.resolve("config").resolve(FILE_NAME);
    }

    public Mode getMode() {
        return mode;
    }

    public List<String> getManualVariants() {
        return manualVariants;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }
}
