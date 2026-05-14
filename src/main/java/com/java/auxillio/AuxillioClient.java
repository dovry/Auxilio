package com.java.auxillio;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Auxillio.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = Auxillio.MODID, value = Dist.CLIENT)
public class AuxillioClient {
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath(Auxillio.MODID, "mouse_tweaks"));
    private static final KeyMapping SPREAD_IN_CRAFTING = new KeyMapping(
            "key.auxillio.spread_in_crafting",
            KeyConflictContext.GUI,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            KEY_CATEGORY
    );
    private static final KeyMapping DRAG_QUICK_MOVE = new KeyMapping(
            "key.auxillio.drag_quick_move",
            KeyConflictContext.GUI,
            KeyModifier.SHIFT,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            KEY_CATEGORY
    );
    private static final Set<Integer> DRAGGED_SLOT_IDS = new HashSet<>();
    private static int activeDragContainerId = -1;

    public AuxillioClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(AuxillioClient::registerKeyMappings);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        Auxillio.LOGGER.info("HELLO FROM CLIENT SETUP");
        Auxillio.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(SPREAD_IN_CRAFTING);
        event.register(DRAG_QUICK_MOVE);
    }

    @SubscribeEvent
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!SPREAD_IN_CRAFTING.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        if (!(menu instanceof InventoryMenu) && !(menu instanceof CraftingMenu)) {
            return;
        }

        if (!menu.getCarried().isEmpty()) {
            return;
        }

        Slot sourceSlot = screen.getHoveredSlot();
        if (sourceSlot == null || !sourceSlot.hasItem()) {
            return;
        }

        ItemStack sourceStack = sourceSlot.getItem();
        List<Slot> targets = collectCraftingTargets(menu, sourceStack);
        if (targets.isEmpty()) {
            return;
        }

        int containerId = menu.containerId;
        mc.gameMode.handleContainerInput(containerId, sourceSlot.index, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(containerId, -999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, mc.player);
        for (Slot target : targets) {
            mc.gameMode.handleContainerInput(containerId, target.index, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, mc.player);
        }
        mc.gameMode.handleContainerInput(containerId, -999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, mc.player);

        if (!menu.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(containerId, sourceSlot.index, 0, ContainerInput.PICKUP, mc.player);
        }

        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!DRAG_QUICK_MOVE.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(event.getMouseButton()))) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        if (menu.containerId != activeDragContainerId) {
            activeDragContainerId = menu.containerId;
            DRAGGED_SLOT_IDS.clear();
        }

        Slot hovered = screen.getHoveredSlot();
        if (hovered == null || !hovered.hasItem()) {
            return;
        }

        if (!DRAGGED_SLOT_IDS.add(hovered.index)) {
            return;
        }

        mc.gameMode.handleContainerInput(menu.containerId, hovered.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            DRAGGED_SLOT_IDS.clear();
            activeDragContainerId = -1;
        }
    }

    private static List<Slot> collectCraftingTargets(AbstractContainerMenu menu, ItemStack sourceStack) {
        List<Slot> targets = new ArrayList<>();
        for (Slot slot : menu instanceof InventoryMenu ? ((InventoryMenu) menu).getInputGridSlots() : ((CraftingMenu) menu).getInputGridSlots()) {
            if (!slot.isActive() || !slot.mayPlace(sourceStack)) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (current.isEmpty()) {
                targets.add(slot);
                continue;
            }
            if (ItemStack.isSameItemSameComponents(current, sourceStack) && current.getCount() < slot.getMaxStackSize(current)) {
                targets.add(slot);
            }
        }
        return targets;
    }
}
