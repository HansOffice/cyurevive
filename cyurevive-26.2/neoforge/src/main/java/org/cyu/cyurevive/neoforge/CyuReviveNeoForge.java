package org.cyu.cyurevive.neoforge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.cyu.cyurevive.PetBedItem;
import org.cyu.cyurevive.HomeMarkerItem;
import org.cyu.cyurevive.PetStructures;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetBedBlock;
import org.cyu.cyurevive.PetService;
import org.cyu.cyurevive.command.PetCommands;

import java.util.ArrayList;
import java.util.List;

@Mod(CyuRevive.MOD_ID)
public class CyuReviveNeoForge {
    public CyuReviveNeoForge(IEventBus bus, ModContainer container) {
        DeferredRegister<Block> blocks = DeferredRegister.create(Registries.BLOCK, CyuRevive.MOD_ID);
        DeferredRegister<Item> items = DeferredRegister.create(Registries.ITEM, CyuRevive.MOD_ID);
        List<DeferredHolder<Item, ? extends Item>> bedItems = new ArrayList<>();
        for (PetBedBlock bed : CyuRevive.PET_BEDS) {
            blocks.register(bed.bedName(), () -> bed);
            bedItems.add(items.register(bed.bedName(), () -> new PetBedItem(bed,
                new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, CyuRevive.id(bed.bedName())))
                    .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("cyurevive.item." + bed.bedName() + ".hint")
                            .withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED).withItalic(false))))))));
        }
        bedItems.add(items.register("home_marker", () -> new HomeMarkerItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, CyuRevive.id("home_marker")))
            .component(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("cyurevive.item.home_marker.hint")
                    .withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED).withItalic(false))))))));
        blocks.register(bus);
        items.register(bus);
        CyuRevive.init(FMLPaths.CONFIGDIR.get(), "neoforge", container.getModInfo().getVersion().toString());

        DeferredRegister<CreativeModeTab> tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CyuRevive.MOD_ID);
        tabs.register("pets", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cyurevive"))
            .icon(() -> new ItemStack(bedItems.getFirst().get()))
            .displayItems((parameters, output) -> bedItems.forEach(item -> output.accept(item.get())))
            .build());
        tabs.register(bus);

        IEventBus game = NeoForge.EVENT_BUS;
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
                int chunkX = event.getChunk().getPos().x();
                int chunkZ = event.getChunk().getPos().z();
                level.getServer().execute(() -> PetService.onFacilityChunkUnloaded(level, chunkX, chunkZ));
            }
        });
        game.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                int chunkX = event.getChunk().getPos().x();
                int chunkZ = event.getChunk().getPos().z();
                level.getServer().execute(() -> PetStructures.onChunkLoad(level, chunkX, chunkZ));
            }
        });
        game.addListener((ServerTickEvent.Post event) -> PetService.tick(event.getServer()));
        game.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PetService.onQuit(player.level().getServer(), player.getUUID());
            }
        });
        game.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PetService.onJoin(player);
        });
        game.addListener((EntityLeaveLevelEvent event) -> PetService.onEntityUnload(event.getEntity()));
        game.addListener((EntityJoinLevelEvent event) -> PetService.onEntityLoad(event.getEntity()));
        game.addListener((RegisterCommandsEvent event) -> PetCommands.register(event.getDispatcher()));
    }
}
