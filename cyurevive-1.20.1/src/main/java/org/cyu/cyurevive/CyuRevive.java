package org.cyu.cyurevive;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CyuRevive {
    public static final String MOD_ID = "cyurevive";
    public static final Logger LOGGER = LoggerFactory.getLogger("CyuRevive");
    public static final PetBedBlock PET_BED = new PetBedBlock.PenBlock();
    public static final PetBedBlock HORSE_STABLE = new PetBedBlock.StableBlock();
    public static final PetBedBlock DOG_BED = new PetBedBlock.DogBlock();
    public static final PetBedBlock CAT_BED = new PetBedBlock.CatBlock();
    public static final PetBedBlock PARROT_BED = new PetBedBlock.ParrotBlock();
    public static final List<PetBedBlock> PET_BEDS = List.of(PET_BED, HORSE_STABLE, DOG_BED, CAT_BED, PARROT_BED);
    public static PetBedBlock facility(BedKind kind) {
        return switch (kind) {
            case PEN -> PET_BED;
            case STABLE -> HORSE_STABLE;
            case DOG -> DOG_BED;
            case CAT -> CAT_BED;
            case PARROT -> PARROT_BED;
        };
    }
    public static volatile PetConfig config;
    private static Path configPath;
    private static String modVersion = "dev";

    public static final int TEXT_DIM = 0x8A96A8;
    public static final int TEXT_BRIGHT = 0xD7DEE8;
    public static final int TEXT_ERROR = 0xF87171;
    public static final int TEXT_SUCCESS = 0x34D399;
    public static final int TEXT_AMBER = 0xFBBF24;
    public static final int TEXT_MUTED = 0x5B6472;
    public static final int TEXT_BORDER = 0x3A4352;

    private CyuRevive() {
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    public static ResourceLocation idForSpecies(String species) {
        return new ResourceLocation("minecraft", species);
    }

    public static boolean isPetBed(BlockState state) {
        return state.getBlock() instanceof PetBedBlock;
    }

    public static void init(Path configDir, String loader, String version) {
        modVersion = version;
        configPath = configDir.resolve(MOD_ID + ".toml");
        boolean freshInstall = Files.notExists(configPath);
        reloadConfig();
        LOGGER.info("--------------------------------------------------");
        LOGGER.info(" CyuRevive - 宠物复活");
        LOGGER.info("");
        LOGGER.info(" ▸ 版本 {} | 加载器 {}", version, loader);
        LOGGER.info(" ▸ 窝容量 {} | 可复活 {} 种 | 交流群 331910315", config.bedCapacity, config.revivableTypes.size());
        LOGGER.info("");
        LOGGER.info(" ▸ 状态 启动完成");
        if (freshInstall) LOGGER.info(" ▸ 首次安装 配置已生成");
        LOGGER.info("--------------------------------------------------");
    }

    public static void onServerStarted() {
        config.resolveItems();
    }

    public static synchronized PetConfig readConfig() {
        return PetConfig.load(configPath, modVersion);
    }

    public static void applyConfig(PetConfig replacement) {
        replacement.resolveItems();
        config = replacement;
    }

    public static void reloadConfig() {
        config = readConfig();
    }
}
