package org.cyu.cyurevive.forge;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.ChunkEvent;
import org.cyu.cyurevive.PetBedItem;
import org.cyu.cyurevive.HomeMarkerItem;
import org.cyu.cyurevive.PetStructures;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetBedBlock;
import org.cyu.cyurevive.PetService;
import org.cyu.cyurevive.command.PetCommands;

import java.util.ArrayList;
import java.util.List;

@Mod(CyuRevive.MOD_ID)
public class CyuReviveForge {
    public CyuReviveForge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        DeferredRegister<Block> blocks = DeferredRegister.create(Registries.BLOCK, CyuRevive.MOD_ID);
        DeferredRegister<Item> items = DeferredRegister.create(Registries.ITEM, CyuRevive.MOD_ID);
        List<RegistryObject<Item>> bedItems = new ArrayList<>();
        for (PetBedBlock bed : CyuRevive.PET_BEDS) {
            blocks.register(bed.bedName(), () -> bed);
            bedItems.add(items.register(bed.bedName(), () -> new PetBedItem(bed, new Item.Properties())));
        }
        bedItems.add(items.register("home_marker", () -> new HomeMarkerItem(new Item.Properties())));
        blocks.register(bus);
        items.register(bus);
        CyuRevive.init(FMLPaths.CONFIGDIR.get(), "forge", ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString());

        DeferredRegister<CreativeModeTab> tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CyuRevive.MOD_ID);
        tabs.register("pets", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cyurevive"))
            .icon(() -> new ItemStack(bedItems.get(0).get()))
            .displayItems((parameters, output) -> bedItems.forEach(item -> output.accept(item.get())))
            .build());
        tabs.register(bus);

        IEventBus game = MinecraftForge.EVENT_BUS;
        game.addListener((ServerStoppingEvent event) -> PetService.onStop(event.getServer()));
        game.addListener((ServerStartedEvent event) -> CyuRevive.onServerStarted());
        game.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            if (PetService.onUseBlock(event.getEntity(), event.getLevel(), event.getPos(), event.getHand())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        game.addListener((PlayerInteractEvent.EntityInteract event) -> {
            if (PetService.onPetInteract(event.getEntity(), event.getTarget(), event.getHand())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        game.addListener((LivingDeathEvent event) -> {
            if (PetService.beforeDeath(event.getEntity(), event.getSource())) {
                event.setCanceled(true);
            }
        });
        game.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                int chunkX = event.getChunk().getPos().x;
                int chunkZ = event.getChunk().getPos().z;
                level.getServer().execute(() -> PetService.onFacilityChunkUnloaded(level, chunkX, chunkZ));
            }
        });
        game.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                int chunkX = event.getChunk().getPos().x;
                int chunkZ = event.getChunk().getPos().z;
                level.getServer().execute(() -> PetStructures.onChunkLoad(level, chunkX, chunkZ));
            }
        });
        game.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) PetService.tick(event.getServer());
        });
        game.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PetService.onQuit(player.level().getServer(), player.getUUID());
            }
        });
        game.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PetService.onJoin(player);
        });
        game.addListener((EntityLeaveLevelEvent event) -> PetService.onEntityUnload(event.getEntity()));
        game.addListener(CyuReviveForge::onEntityJoin);
        game.addListener((RegisterCommandsEvent event) -> PetCommands.register(event.getDispatcher()));
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Animal)) return;
        Entity pet = event.getEntity();
        var server = level.getServer();
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (level.getEntity(pet.getUUID()) == pet) PetService.onEntityLoad(pet);
        }));
    }
}
