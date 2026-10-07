package org.cyu.cyurevive.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.cyu.cyurevive.PetBedItem;
import org.cyu.cyurevive.HomeMarkerItem;
import org.cyu.cyurevive.PetStructures;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetBedBlock;
import org.cyu.cyurevive.PetService;
import org.cyu.cyurevive.command.PetCommands;

import java.util.ArrayList;
import java.util.List;

public class CyuReviveFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        List<Item> bedItems = new ArrayList<>();
        for (PetBedBlock bed : CyuRevive.PET_BEDS) {
            Registry.register(BuiltInRegistries.BLOCK, CyuRevive.id(bed.bedName()), bed);
            bedItems.add(Registry.register(
                BuiltInRegistries.ITEM,
                CyuRevive.id(bed.bedName()),
                new PetBedItem(bed, new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, CyuRevive.id(bed.bedName())))
                    .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("cyurevive.item." + bed.bedName() + ".hint")
                            .withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED).withItalic(false))))))));
        }
        bedItems.add(Registry.register(BuiltInRegistries.ITEM, CyuRevive.id("home_marker"),
            new HomeMarkerItem(new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, CyuRevive.id("home_marker")))
                .component(DataComponents.LORE, new ItemLore(List.of(
                    Component.translatable("cyurevive.item.home_marker.hint")
                        .withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED).withItalic(false))))))));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CyuRevive.id("pets"), FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cyurevive"))
            .icon(() -> new ItemStack(bedItems.getFirst()))
            .displayItems((parameters, output) -> bedItems.forEach(output::accept)).build());
        String version = FabricLoader.getInstance().getModContainer(CyuRevive.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
        CyuRevive.init(FabricLoader.getInstance().getConfigDir(), "fabric", version);

        ServerLifecycleEvents.SERVER_STOPPING.register(PetService::onStop);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> CyuRevive.onServerStarted());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            PetCommands.register(dispatcher));

        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            PetService.onUseBlock(player, level, hit.getBlockPos(), hand)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
            PetService.onPetInteract(player, entity, hand)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) ->
            !PetService.beforeDeath(entity, source));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            level.getServer().execute(() -> PetService.onFacilityChunkUnloaded(level, chunkX, chunkZ));
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            level.getServer().execute(() -> PetStructures.onChunkLoad(level, chunkX, chunkZ));
        });
        ServerTickEvents.END_SERVER_TICK.register(PetService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            PetService.onQuit(server, handler.getPlayer().getUUID()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PetService.onJoin(handler.getPlayer()));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> PetService.onEntityUnload(entity));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> PetService.onEntityLoad(entity));
    }
}
