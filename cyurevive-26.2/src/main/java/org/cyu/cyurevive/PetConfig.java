package org.cyu.cyurevive;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PetConfig {
    public static final int CURRENT_CONFIG_VERSION = 100;
    public static final int CURRENT_CONFIG_LAYOUT = 5;
    private static final Set<String> VERSION_KEYS = Set.of("config-version", "config-layout");

    public double bindRadius = 12.0;
    public int bedCapacity = 1;
    public int maxPetsPerPlayer = 8;
    public boolean reviveOnSleep = true;
    public int reviveCooldownSeconds = 180;
    public boolean recallEnabled = true;
    public boolean commandRecall = true;
    public Identifier recallItem = null;
    public int recallItemCount = 1;
    public boolean downedEnabled = true;
    public int downedSeconds = 60;
    public List<Identifier> downedItems = List.of(Identifier.withDefaultNamespace("bone"));
    public double downedHealPercent = 0.5;
    public double respawnHealthPercent = 1.0;
    public int respawnProtectionSeconds = 5;
    public boolean broadcastDeathMessage = false;
    public boolean bedPrivate = false;
    public boolean nightReturn = true;
    public boolean reviveCeremony = true;
    public boolean bedAura = true;
    public int rescueRegenSeconds = 5;
    public Set<Identifier> revivableTypes = Set.of(
        Identifier.withDefaultNamespace("wolf"),
        Identifier.withDefaultNamespace("cat"),
        Identifier.withDefaultNamespace("parrot"),
        Identifier.withDefaultNamespace("horse"),
        Identifier.withDefaultNamespace("donkey"),
        Identifier.withDefaultNamespace("mule"),
        Identifier.withDefaultNamespace("llama"),
        Identifier.withDefaultNamespace("trader_llama"));
    public Set<Item> downedItemSet = Set.of();

    public static PetConfig load(Path path, String modVersion) {
        PetConfig config = new PetConfig();
        try {
            if (Files.notExists(path)) {
                if (Files.exists(path.resolveSibling("cyurevive.json"))) {
                    throw new IllegalArgumentException("配置已改用 cyurevive.toml，请按新模板转录旧 JSON 中的自定义参数，旧文件不会被覆盖");
                }
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT_FILE);
            } else {
                UnmodifiableConfig values = readToml(path);
                int version = intOr(values, "config-version");
                int layout = intOr(values, "config-layout");
                if (version > CURRENT_CONFIG_VERSION || layout > CURRENT_CONFIG_LAYOUT) {
                    throw new IllegalArgumentException("配置来自更新的模组版本或布局: " + version + "/" + layout);
                }
                config.applyToml(values);
                config.validateValues();
                if (version < CURRENT_CONFIG_VERSION || layout < CURRENT_CONFIG_LAYOUT) {
                    upgrade(path, values, version, layout, modVersion);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读写配置 " + path, e);
        }
        config.validateValues();
        return config;
    }

    private static UnmodifiableConfig readToml(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            CommentedConfig config = new TomlParser().parse(reader);
            UnmodifiableConfig template = new TomlParser().parse(DEFAULT_FILE);
            for (String key : config.valueMap().keySet()) {
                if (!template.contains(key)) throw new IllegalArgumentException("未知配置项: " + key);
            }
            return config;
        }
    }

    private static int intOr(UnmodifiableConfig config, String key) {
        return integer(config, key, 0);
    }

    private static void upgrade(Path path, UnmodifiableConfig old, int version, int layout, String modVersion) throws IOException {
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(new Date());
        Path backupDir = path.getParent().resolve("cyurevive-backup").resolve(modVersion + "-" + stamp);
        int suffix = 2;
        while (Files.exists(backupDir)) {
            backupDir = path.getParent().resolve("cyurevive-backup").resolve(modVersion + "-" + stamp + "-" + suffix++);
        }
        Files.createDirectories(backupDir);
        Files.copy(path, backupDir.resolve(path.getFileName().toString()));

        Merged merged = mergeTemplate(old);
        Path tmp = path.resolveSibling(".cyurevive.tmp");
        Files.writeString(tmp, merged.text());
        moveAtomic(tmp, path);

        Files.writeString(backupDir.resolve("upgrade-note.txt"), """
            CyuRevive 已为当前版本生成新的默认配置
            旧配置文件已完整备份至当前目录
            你的自定义参数已自动继承到新配置中，无需重新配置
            """ + "旧配置版本: " + (version > 0 ? version : "未标记") + "\n"
            + "旧布局协议: " + layout + "\n"
            + "新配置版本: " + CURRENT_CONFIG_VERSION + "\n"
            + "新布局协议: " + CURRENT_CONFIG_LAYOUT + "\n");
        CyuRevive.LOGGER.info("检测到旧版配置（版本: {}, 布局: {}），已备份至 {}", version > 0 ? version : "未标记", layout, backupDir);
        CyuRevive.LOGGER.info("已自动迁移并继承 {} 项自定义参数，升级至配置版本 {} (布局协议 {})", merged.count(), CURRENT_CONFIG_VERSION, CURRENT_CONFIG_LAYOUT);
    }

    private record Merged(String text, int count) {
    }

    private static Merged mergeTemplate(UnmodifiableConfig old) {
        CommentedConfig merged = new TomlParser().parse(DEFAULT_FILE);
        int migrated = 0;
        for (String key : old.valueMap().keySet()) {
            if (VERSION_KEYS.contains(key)) continue;
            if (!merged.contains(key)) throw new IllegalArgumentException("未知配置项: " + key);
            merged.set(key, old.get(key));
            migrated++;
        }
        return new Merged(new TomlWriter().writeToString(merged), migrated);
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void applyToml(UnmodifiableConfig values) {
        bindRadius = num(values, "bind-radius", bindRadius);
        bedCapacity = integer(values, "bed-capacity", bedCapacity);
        maxPetsPerPlayer = integer(values, "max-pets-per-player", maxPetsPerPlayer);
        reviveOnSleep = bool(values, "revive-on-sleep", reviveOnSleep);
        reviveCooldownSeconds = integer(values, "revive-cooldown-seconds", reviveCooldownSeconds);
        recallEnabled = bool(values, "recall-enabled", recallEnabled);
        commandRecall = bool(values, "command-recall", commandRecall);
        String recallRaw = text(values, "recall-item");
        recallItem = recallRaw != null && !recallRaw.isBlank() ? parseLoc(recallRaw) : null;
        recallItemCount = integer(values, "recall-item-count", recallItemCount);
        downedEnabled = bool(values, "downed-enabled", downedEnabled);
        downedSeconds = integer(values, "downed-seconds", downedSeconds);
        downedItems = locList(values, "downed-items", downedItems);
        downedHealPercent = num(values, "downed-heal-percent", downedHealPercent);
        respawnHealthPercent = num(values, "respawn-health-percent", respawnHealthPercent);
        respawnProtectionSeconds = integer(values, "respawn-protection-seconds", respawnProtectionSeconds);
        broadcastDeathMessage = bool(values, "broadcast-death-message", broadcastDeathMessage);
        bedPrivate = bool(values, "bed-private", bedPrivate);
        nightReturn = bool(values, "night-return", nightReturn);
        reviveCeremony = bool(values, "revive-ceremony", reviveCeremony);
        bedAura = bool(values, "bed-aura", bedAura);
        rescueRegenSeconds = integer(values, "rescue-regen-seconds", rescueRegenSeconds);
        revivableTypes = Set.copyOf(locList(values, "revivable-types", List.copyOf(revivableTypes)));
    }

    private void validateValues() {
        if (!Double.isFinite(bindRadius) || bindRadius < 2 || bindRadius > 64
            || bedCapacity < 1 || maxPetsPerPlayer < -1 || reviveCooldownSeconds < 0
            || recallItemCount < 1 || downedSeconds < 1 || respawnProtectionSeconds < 0
            || rescueRegenSeconds < 0 || rescueRegenSeconds > 60
            || !Double.isFinite(downedHealPercent) || downedHealPercent <= 0 || downedHealPercent > 1
            || !Double.isFinite(respawnHealthPercent) || respawnHealthPercent <= 0 || respawnHealthPercent > 1) {
            throw new IllegalArgumentException("宠物配置中的范围、数量、时长或血量比例无效");
        }
    }

    public void resolveItems() {
        if (recallItem != null && (BuiltInRegistries.ITEM.getOptional(recallItem).isEmpty()
            || BuiltInRegistries.ITEM.getOptional(recallItem).orElseThrow() == net.minecraft.world.item.Items.AIR)) {
            throw new IllegalArgumentException("召回消耗物品不存在: " + recallItem);
        }
        Set<Item> items = new HashSet<>();
        for (Identifier id : downedItems) {
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(
                () -> new IllegalArgumentException("救援物品不存在: " + id));
            if (item == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("救援物品不能是空气");
            items.add(item);
        }
        for (Identifier id : revivableTypes) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                throw new IllegalArgumentException("宠物实体类型不存在: " + id);
            }
        }
        downedItemSet = Set.copyOf(items);
    }

    private static Identifier parseLoc(String raw) {
        return Identifier.parse(raw);
    }

    private static double num(UnmodifiableConfig config, String key, double fallback) {
        if (!config.contains(key)) return fallback;
        Object value = config.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " 必须是数值");
        return number.doubleValue();
    }

    private static int integer(UnmodifiableConfig config, String key, int fallback) {
        if (!config.contains(key)) return fallback;
        Object value = config.get(key);
        if (!(value instanceof Long || value instanceof Integer)) throw new IllegalArgumentException(key + " 必须是整数");
        return Math.toIntExact(((Number) value).longValue());
    }

    private static boolean bool(UnmodifiableConfig config, String key, boolean fallback) {
        if (!config.contains(key)) return fallback;
        Object value = config.get(key);
        if (!(value instanceof Boolean enabled)) throw new IllegalArgumentException(key + " 必须是 true 或 false");
        return enabled;
    }

    private static String text(UnmodifiableConfig config, String key) {
        if (!config.contains(key)) return null;
        Object value = config.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " 必须是字符串");
        return text;
    }

    private static List<Identifier> locList(UnmodifiableConfig config, String key, List<Identifier> fallback) {
        if (!config.contains(key)) return fallback;
        Object value = config.get(key);
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(key + " 必须是物品或实体 ID 列表");
        return values.stream().map(element -> {
            if (!(element instanceof String id)) throw new IllegalArgumentException(key + " 中的 ID 必须是字符串");
            return parseLoc(id);
        }).toList();
    }

    private static final String DEFAULT_FILE = defaultTemplate();

    private static String defaultTemplate() {
        var stream = java.util.Objects.requireNonNull(PetConfig.class.getResourceAsStream("/config/cyurevive.toml"), "Missing default pet configuration");
        try (stream) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read default pet configuration", failure);
        }
    }
}
