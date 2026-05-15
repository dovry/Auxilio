package com.java.auxillio;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
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

        if (!spreadInCrafting(menu, sourceSlot, mc)) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!SPREAD_IN_CRAFTING.isActiveAndMatches(InputConstants.getKey(event.getKeyEvent()))) {
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

        if (!spreadInCrafting(menu, sourceSlot, mc)) {
            return;
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

    @SubscribeEvent
    static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        double deltaY = event.getScrollDeltaY();
        if (deltaY == 0.0D) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        Slot hovered = screen.getHoveredSlot();
        if (hovered == null) {
            return;
        }

        boolean handled;
        if (deltaY > 0) {
            handled = sendOneToOppositeInventory(menu, hovered, mc);
        } else {
            handled = sendOneToPlayerInventory(menu, hovered, mc);
        }

        if (handled) {
            event.setCanceled(true);
        }
    }

    private static boolean sendOneToPlayerInventory(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        if (!hovered.hasItem() || !menu.getCarried().isEmpty() || isPlayerInventorySlot(hovered, mc.player.getInventory())) {
            return false;
        }

        ItemStack source = hovered.getItem();
        Slot target = findSingleItemTargetInPlayerInventory(menu, hovered, source, mc.player.getInventory());
        if (target == null) {
            return false;
        }

        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        click(menu, target, 1, ContainerInput.PICKUP, mc);
        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        debug("scrollDown moved one item from slot {} to player slot {}", hovered.index, target.index);
        return true;
    }

    private static boolean sendOneToOppositeInventory(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        if (!hovered.hasItem() || !menu.getCarried().isEmpty()) {
            return false;
        }

        ItemStack source = hovered.getItem();
        Slot target = findSingleItemTarget(menu, hovered, source, mc.player.getInventory());
        if (target == null) {
            return false;
        }

        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        click(menu, target, 1, ContainerInput.PICKUP, mc);
        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        debug("scrollUp moved one item from slot {} to slot {}", hovered.index, target.index);
        return true;
    }

    private static Slot findSingleItemTarget(AbstractContainerMenu menu, Slot sourceSlot, ItemStack sourceStack, Inventory playerInventory) {
        boolean sourceIsPlayer = isPlayerInventorySlot(sourceSlot, playerInventory);
        Slot emptyCandidate = null;

        for (Slot slot : menu.slots) {
            if (slot == null || slot == sourceSlot || !slot.isActive()) {
                continue;
            }
            if (isPlayerInventorySlot(slot, playerInventory) == sourceIsPlayer) {
                continue;
            }
            if (!slot.mayPlace(sourceStack)) {
                continue;
            }

            ItemStack current = slot.getItem();
            if (!current.isEmpty()) {
                if (ItemStack.isSameItemSameComponents(current, sourceStack) && current.getCount() < slot.getMaxStackSize(current)) {
                    return slot;
                }
            } else if (emptyCandidate == null) {
                emptyCandidate = slot;
            }
        }

        return emptyCandidate;
    }

    private static Slot findSingleItemTargetInPlayerInventory(AbstractContainerMenu menu, Slot sourceSlot, ItemStack sourceStack, Inventory playerInventory) {
        Slot emptyCandidate = null;

        for (Slot slot : menu.slots) {
            if (slot == null || slot == sourceSlot || !slot.isActive() || !isPlayerInventorySlot(slot, playerInventory)) {
                continue;
            }
            if (!slot.mayPlace(sourceStack)) {
                continue;
            }

            ItemStack current = slot.getItem();
            if (!current.isEmpty()) {
                if (ItemStack.isSameItemSameComponents(current, sourceStack) && current.getCount() < slot.getMaxStackSize(current)) {
                    return slot;
                }
            } else if (emptyCandidate == null) {
                emptyCandidate = slot;
            }
        }

        return emptyCandidate;
    }

    private static boolean isPlayerInventorySlot(Slot slot, Inventory inventory) {
        return slot.container == inventory;
    }

    private static boolean spreadInCrafting(AbstractContainerMenu menu, Slot sourceSlot, Minecraft mc) {
        ItemStack sourceStack = sourceSlot.getItem();
        if (sourceStack.isEmpty()) {
            return false;
        }

        List<Slot> craftSlots = menu instanceof InventoryMenu ? ((InventoryMenu) menu).getInputGridSlots() : ((CraftingMenu) menu).getInputGridSlots();
        List<Slot> eligibleSlots = new ArrayList<>();
        for (Slot slot : craftSlots) {
            if (!slot.isActive() || !slot.mayPlace(sourceStack)) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (current.isEmpty() || ItemStack.isSameItemSameComponents(current, sourceStack)) {
                eligibleSlots.add(slot);
            }
        }

        if (eligibleSlots.isEmpty()) {
            return false;
        }

        boolean sourceInCraftGrid = craftSlots.contains(sourceSlot);
        if (!sourceInCraftGrid) {
            int containerId = menu.containerId;
            debug("spread(start external) sourceSlot={} craftBefore={}", sourceSlot.index, craftGridCounts(craftSlots, sourceStack));
            click(menu, sourceSlot, 0, ContainerInput.PICKUP, mc);
            clickRaw(containerId, -999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, mc);
            for (Slot target : eligibleSlots) {
                click(menu, target, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, mc);
            }
            clickRaw(containerId, -999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, mc);
            if (!menu.getCarried().isEmpty()) {
                click(menu, sourceSlot, 0, ContainerInput.PICKUP, mc);
            }
            debug("spread(end external) sourceSlot={} craftAfter={}", sourceSlot.index, craftGridCounts(craftSlots, sourceStack));
            return true;
        }

        int total = 0;
        List<Slot> sameItemSlots = new ArrayList<>();
        for (Slot slot : eligibleSlots) {
            ItemStack current = slot.getItem();
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, sourceStack)) {
                sameItemSlots.add(slot);
                total += current.getCount();
            }
        }

        if (total <= 0 || sameItemSlots.isEmpty()) {
            return false;
        }
        debug("spread(start internal) sourceSlot={} total={} craftBefore={}", sourceSlot.index, total, craftGridCounts(craftSlots, sourceStack));

        List<Integer> currentCounts = new ArrayList<>(eligibleSlots.size());
        int slotCount = eligibleSlots.size();
        int base = total / slotCount;
        int remainder = total % slotCount;

        List<Slot> donors = new ArrayList<>();
        List<Slot> receivers = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = eligibleSlots.get(i);
            ItemStack current = slot.getItem();
            int count = !current.isEmpty() && ItemStack.isSameItemSameComponents(current, sourceStack) ? current.getCount() : 0;
            currentCounts.add(count);

            int desired = base + (i < remainder ? 1 : 0);
            if (count > desired) {
                for (int j = 0; j < count - desired; j++) {
                    donors.add(slot);
                }
            } else if (count < desired) {
                for (int j = 0; j < desired - count; j++) {
                    receivers.add(slot);
                }
            }
        }

        if (donors.isEmpty() || receivers.isEmpty()) {
            return true;
        }

        int moves = Math.min(donors.size(), receivers.size());
        for (int i = 0; i < moves; i++) {
            Slot from = donors.get(i);
            Slot to = receivers.get(i);
            moveSingleItem(menu, from, to, mc);
        }
        debug("spread(end internal) sourceSlot={} base={} rem={} moves={} craftAfter={}", sourceSlot.index, base, remainder, moves, craftGridCounts(craftSlots, sourceStack));

        return true;
    }

    private static void moveSingleItem(AbstractContainerMenu menu, Slot from, Slot to, Minecraft mc) {
        if (from == to || !from.hasItem()) {
            return;
        }

        click(menu, from, 0, ContainerInput.PICKUP, mc);
        click(menu, to, 1, ContainerInput.PICKUP, mc);
        click(menu, from, 0, ContainerInput.PICKUP, mc);
    }

    private static void click(AbstractContainerMenu menu, Slot slot, int button, ContainerInput input, Minecraft mc) {
        clickRaw(menu.containerId, slot.index, button, input, mc);
    }

    private static void clickRaw(int containerId, int slotIndex, int button, ContainerInput input, Minecraft mc) {
        mc.gameMode.handleContainerInput(containerId, slotIndex, button, input, mc.player);
    }

    private static List<Integer> craftGridCounts(List<Slot> craftSlots, ItemStack reference) {
        List<Integer> counts = new ArrayList<>(craftSlots.size());
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            counts.add(!current.isEmpty() && ItemStack.isSameItemSameComponents(current, reference) ? current.getCount() : 0);
        }
        return counts;
    }

    private static void debug(String message, Object... args) {
        if (Config.DEBUG_MOUSE_TWEAKS.getAsBoolean()) {
            Auxillio.LOGGER.info("[MouseTweaks] " + message, args);
        }
    }
}
