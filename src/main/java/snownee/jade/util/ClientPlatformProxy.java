package snownee.jade.util;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.block.BlockPickInteractionAware;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.entity.EntityPickInteractionAware;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import snownee.jade.Jade;
import snownee.jade.JadeClient;
import snownee.jade.api.Identifiers;
import snownee.jade.api.ui.IElement;
import snownee.jade.command.DumpHandlersCommand;
import snownee.jade.compat.REICompat;
import snownee.jade.impl.ObjectDataCenter;
import snownee.jade.impl.config.PluginConfig;
import snownee.jade.impl.ui.FluidStackElement;
import snownee.jade.overlay.DatapackBlockManager;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.overlay.WailaTickHandler;

public final class ClientPlatformProxy {

	@Nullable
	public static String getLastKnownUsername(UUID uuid) {
		return null;// UsernameCache.getLastKnownUsername(uuid);
	}

	public static void initModNames(Map<String, String> map) {
		List<ModContainer> mods = ImmutableList.copyOf(FabricLoader.getInstance().getAllMods());
		for (ModContainer mod : mods) {
			String modid = mod.getMetadata().getId();
			String name = mod.getMetadata().getName();
			if (Strings.isNullOrEmpty(name)) {
				StringUtils.capitalize(modid);
			}
			map.put(modid, name);
		}
	}

	public static void init() {
		ClientEntityEvents.ENTITY_LOAD.register(ClientPlatformProxy::onEntityJoin);
		ClientEntityEvents.ENTITY_UNLOAD.register(ClientPlatformProxy::onEntityLeave);
		ResourceLocation lowest = new ResourceLocation(Jade.MODID, "mod_name");
		ItemTooltipCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, lowest);
		ItemTooltipCallback.EVENT.register(lowest, ClientPlatformProxy::onTooltip);
		ClientTickEvents.END_CLIENT_TICK.register(ClientPlatformProxy::onClientTick);
		HudRenderCallback.EVENT.register(ClientPlatformProxy::onRenderTick);
		ClientPlayConnectionEvents.DISCONNECT.register(ClientPlatformProxy::onPlayerLeave);
		ClientTickEvents.END_CLIENT_TICK.register(ClientPlatformProxy::onKeyPressed);
		ScreenEvents.AFTER_INIT.register((Minecraft client, Screen screen, int scaledWidth, int scaledHeight) -> ScreenEvents.afterRender(screen).register(ClientPlatformProxy::onGui));

		ClientPlayNetworking.registerGlobalReceiver(Identifiers.PACKET_RECEIVE_DATA, (client, handler, buf, responseSender) -> {
			CompoundTag nbt = buf.readNbt();
			client.execute(() -> {
				ObjectDataCenter.setServerData(nbt);
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(Identifiers.PACKET_SERVER_PING, (client, handler, buf, responseSender) -> {
			int size = buf.readVarInt();
			Map<ResourceLocation, Boolean> forcedKeys = Maps.newHashMap();
			for (int i = 0; i < size; i++) {
				ResourceLocation id = new ResourceLocation(buf.readUtf(128));
				boolean value = buf.readBoolean();
				forcedKeys.put(id, value);
			}
			client.execute(() -> {
				ObjectDataCenter.serverConnected = true;
				forcedKeys.forEach(PluginConfig.INSTANCE::set);
				Jade.LOGGER.info("Received config from the server: {}", new Gson().toJson(forcedKeys));
			});
		});

		DumpHandlersCommand.register(ClientCommandManager.DISPATCHER);

	}

	public static void onEntityJoin(Entity entity, ClientLevel level) {
		DatapackBlockManager.onEntityJoin(entity);
	}

	public static void onEntityLeave(Entity entity, ClientLevel level) {
		DatapackBlockManager.onEntityLeave(entity);
	}

	public static void onTooltip(ItemStack stack, TooltipFlag context, List<Component> lines) {
		JadeClient.onTooltip(lines, stack);
	}

	public static void onRenderTick(PoseStack matrixStack, float tickDelta) {
		if (Minecraft.getInstance().screen == null) {
			OverlayRenderer.renderOverlay(matrixStack);
		}
	}

	public static void onClientTick(Minecraft mc) {
		WailaTickHandler.instance().tickClient();
	}

	public static void onPlayerLeave(ClientPacketListener handler, Minecraft client) {
		ObjectDataCenter.serverConnected = false;
	}

	public static void onKeyPressed(Minecraft mc) {
		JadeClient.onKeyPressed(1);
		if (JadeClient.showUses != null) {
			REICompat.onKeyPressed(1);
		}
	}

	public static void onGui(Screen screen, PoseStack matrices, int mouseX, int mouseY, float tickDelta) {
		JadeClient.onGui(screen);
	}

	public static KeyMapping registerKeyBinding(String desc, int defaultKey) {
		KeyMapping key = new KeyMapping("key.jade." + desc, InputConstants.Type.KEYSYM, defaultKey, Jade.NAME);
		KeyBindingHelper.registerKeyBinding(key);
		return key;
	}

	public static boolean shouldRegisterRecipeViewerKeys() {
		return FabricLoader.getInstance().isModLoaded("roughlyenoughitems");
	}

	public static void requestBlockData(BlockEntity blockEntity, boolean showDetails) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(blockEntity.getBlockPos());
		buf.writeBoolean(showDetails);
		ClientPlayNetworking.send(Identifiers.PACKET_REQUEST_TILE, buf);
	}

	public static void requestEntityData(Entity entity, boolean showDetails) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(entity.getId());
		buf.writeBoolean(showDetails);
		ClientPlayNetworking.send(Identifiers.PACKET_REQUEST_ENTITY, buf);
	}

	public static ItemStack getEntityPickedResult(Entity entity, Player player, EntityHitResult hitResult) {
		if (entity instanceof EntityPickInteractionAware) {
			return ((EntityPickInteractionAware) entity).getPickedStack(player, hitResult);
		}
		return entity.getPickResult();
	}

	public static ItemStack getBlockPickedResult(BlockState state, Player player, BlockHitResult hitResult) {
		Block block = state.getBlock();
		if (block instanceof BlockPickInteractionAware) {
			return ((BlockPickInteractionAware) block).getPickedStack(state, null, null, player, hitResult);
		}
		return block.getCloneItemStack(player.level, hitResult.getBlockPos(), state);
	}

	public static IElement elementFromLiquid(LiquidBlock block) {
		FluidState fluidState = block.getFluidState(block.defaultBlockState());
		return new FluidStackElement(fluidState);//.size(new Size(18, 18));
	}

	public static void registerReloadListener(ResourceManagerReloadListener listener) {
		Minecraft.getInstance().execute(() -> {
			ReloadableResourceManager manager = (ReloadableResourceManager) Minecraft.getInstance().getResourceManager();
			manager.registerReloadListener(listener);
			listener.onResourceManagerReload(manager);
		});
	}

}