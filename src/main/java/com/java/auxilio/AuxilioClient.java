package com.java.auxilio;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Auxilio.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = Auxilio.MODID, value = Dist.CLIENT)
public class AuxilioClient {
    private static final long SHIFT_DOUBLE_CLICK_WINDOW_MS = 300L;
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath(Auxilio.MODID, "mouse_tweaks"));
    private static final KeyMapping SPREAD_IN_CRAFTING = new KeyMapping(
            "key.auxilio.spread_in_crafting",
            KeyConflictContext.GUI,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            KEY_CATEGORY
    );
    private static final KeyMapping DRAG_QUICK_MOVE = new KeyMapping(
            "key.auxilio.drag_quick_move",
            KeyConflictContext.GUI,
            KeyModifier.SHIFT,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            KEY_CATEGORY
    );
    private static final Set<Integer> DRAGGED_SLOT_IDS = new HashSet<>();
    private static int activeDragContainerId = -1;
    private static long lastShiftLeftClickAt = 0L;
    private static ItemStack lastShiftLeftType = ItemStack.EMPTY;
    private static boolean customRightDragActive = false;
    private static int customRightDragLastSlot = -1;
    private static int pendingVirtualPullContainerId = -1;
    private static int pendingVirtualPullSourceSlot = -1;
    private static int pendingVirtualPullPreferredPlayerSlot = -1;
    private static int pendingVirtualPullTicks = 0;
    private static int pendingVirtualPullCleanupTicks = 0;

    public AuxilioClient(IEventBus modEventBus, ModContainer container) {
        debug("enter AuxilioClient");
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(AuxilioClient::registerKeyMappings);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        debug("enter onClientSetup");
        // Some client setup code
        Auxilio.LOGGER.info("HELLO FROM CLIENT SETUP");
        Auxilio.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        Auxilio.LOGGER.info("MouseTweaks debug toggle (debugMouseTweaks) = {}", Config.DEBUG_MOUSE_TWEAKS.getAsBoolean());
    }

    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        debug("enter registerKeyMappings");
        event.registerCategory(KEY_CATEGORY);
        event.register(SPREAD_IN_CRAFTING);
        event.register(DRAG_QUICK_MOVE);
    }

    @SubscribeEvent
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        debug("enter onMousePressed");
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();

        // Optional custom RMB drag mechanic: places once per slot entry, including revisits.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && Config.ENABLE_REPEAT_RIGHT_DRAG.getAsBoolean()
                && !menu.getCarried().isEmpty()) {
            customRightDragActive = true;
            customRightDragLastSlot = -1;
            // Do not cancel press: keep vanilla single right-click behavior intact.
            debug("customRmbDrag start carried={}", menu.getCarried().getCount());
            return;
        }

        Slot sourceSlot = screen.getHoveredSlot();
        if (sourceSlot == null) {
            return;
        }

        boolean handled = false;
        List<Slot> craftSlots = getCraftSlots(menu);
        if (Config.ENABLE_SHIFT_DOUBLE_CLICK_BULK_MOVE.getAsBoolean()
                && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && event.getMouseButtonEvent().hasShiftDown()
                && menu.getCarried().isEmpty()
                && isPlayerInventorySlot(menu, sourceSlot, mc.player.getInventory())) {
            long now = System.currentTimeMillis();
            boolean withinWindow = now - lastShiftLeftClickAt <= SHIFT_DOUBLE_CLICK_WINDOW_MS;
            boolean vanillaDouble = event.isDoubleClick();
            if ((withinWindow || vanillaDouble) && !lastShiftLeftType.isEmpty()) {
                handled = quickMoveAllOfTypeFromPlayer(menu, lastShiftLeftType.copyWithCount(1), mc);
                debug("shiftDoubleLeft trigger window={}ms vanillaDouble={} type={} handled={}",
                        now - lastShiftLeftClickAt, vanillaDouble, lastShiftLeftType.getItem(), handled);
                lastShiftLeftClickAt = 0L;
                lastShiftLeftType = ItemStack.EMPTY;
            } else if (sourceSlot.hasItem()) {
                lastShiftLeftClickAt = now;
                lastShiftLeftType = sourceSlot.getItem().copyWithCount(1);
                debug("shiftDoubleLeft armed slot={} type={}", sourceSlot.index, lastShiftLeftType.getItem());
            }
        } else if (!craftSlots.isEmpty()
                && menu.getCarried().isEmpty()
                && Config.ENABLE_SPREAD_SORT.getAsBoolean()
                && isKeyFeatureEnabled(SPREAD_IN_CRAFTING)
                && matchesSpreadMouse(event)
                && craftSlots.contains(sourceSlot)) {
            handled = sortCraftGrid(menu, craftSlots, mc);
        }

        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        InputConstants.Key pressedKey = InputConstants.getKey(event.getKeyEvent());
        if (pressedKey.equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT))
                || pressedKey.equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT))) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!Config.ENABLE_SPREAD_SORT.getAsBoolean() || !isKeyFeatureEnabled(SPREAD_IN_CRAFTING)) {
            return;
        }
        if (!matchesSpreadKey(event)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        if (!isSpreadSupportedMenu(menu)) {
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            return;
        }

        Slot sourceSlot = screen.getHoveredSlot();
        if (sourceSlot == null) {
            return;
        }

        List<Slot> craftSlots = getCraftSlots(menu);
        boolean handled = false;
        if (craftSlots.contains(sourceSlot)) {
            handled = sortCraftGrid(menu, craftSlots, mc);
        }

        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        debug("enter onMouseDragged");
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!Config.ENABLE_SHIFT_DRAG_QUICK_MOVE.getAsBoolean() || !isKeyFeatureEnabled(DRAG_QUICK_MOVE)) {
            return;
        }
        if (customRightDragActive && event.getMouseButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode == null || mc.player == null) {
                return;
            }

            AbstractContainerMenu menu = screen.getMenu();
            ItemStack carried = menu.getCarried();
            Slot hovered = screen.getHoveredSlot();

            if (hovered == null) {
                // Reset so re-entering the same slot can place again.
                customRightDragLastSlot = -1;
                event.setCanceled(true);
                return;
            }

            if (carried.isEmpty()) {
                customRightDragActive = false;
                customRightDragLastSlot = -1;
                return;
            }

            // Simulate one right-click for each new hovered slot in this drag pass.
            if (hovered.index != customRightDragLastSlot) {
                if (canIncrementSameStack(hovered, carried)) {
                    click(menu, hovered, 1, ContainerInput.PICKUP, mc);
                    debug("customRmbDrag increment slot={} remaining={}", hovered.index, menu.getCarried().getCount());
                }
                customRightDragLastSlot = hovered.index;
            }

            event.setCanceled(true);
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
        debug("enter onMouseReleased");
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && customRightDragActive) {
            customRightDragActive = false;
            customRightDragLastSlot = -1;
            // Do not cancel release: keep vanilla RMB interactions functional.
            debug("customRmbDrag end");
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            DRAGGED_SLOT_IDS.clear();
            activeDragContainerId = -1;
        }
    }

    @SubscribeEvent
    static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        debug("enter onMouseScrolled");
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!Config.ENABLE_SCROLL_TRANSFER.getAsBoolean()) {
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
        boolean hoveredIsPlayer = isPlayerInventorySlot(menu, hovered, mc.player.getInventory());
        if (mc.player.getAbilities().instabuild && !isPlayerInventorySlot(menu, hovered, mc.player.getInventory())) {
            debug("scroll skipped in creative for non-player slot {}", hovered.index);
            return;
        }

        if (Config.ENABLE_SHIFT_SCROLL_FURNACE_FUEL.getAsBoolean()
                && isShiftHeld(mc)
                && menu instanceof AbstractFurnaceMenu
                && isPlayerInventorySlot(menu, hovered, mc.player.getInventory())) {
            boolean fuelHandled = sendOneToFurnaceFuelSlot(menu, hovered, mc);
            if (fuelHandled) {
                event.setCanceled(true);
                return;
            }
        }

        boolean handled = deltaY > 0
                ? handleScrollUp(menu, hovered, hoveredIsPlayer, mc)
                : handleScrollDown(menu, hovered, hoveredIsPlayer, mc);

        if (handled) {
            event.setCanceled(true);
        }
    }

    private static boolean sendOneToPlayerInventory(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        debug("enter sendOneToPlayerInventory");
        Profiler.get().push("auxilio_scroll_down_one_to_player");
        try {
        if (pendingVirtualPullTicks > 0) {
            return false;
        }
        if (!hovered.hasItem() || !menu.getCarried().isEmpty() || isPlayerInventorySlot(menu, hovered, mc.player.getInventory())) {
            return false;
        }

        ItemStack source = hovered.getItem();
        Slot target = findSingleItemTargetInPlayerInventory(menu, hovered, source, mc.player.getInventory());
        if (target == null) {
            return false;
        }

        // For virtual/modded inventory slots, try extracting via carried stack interaction.
        if (isVirtualInventorySlot(menu, hovered, mc.player.getInventory())) {
            boolean moved = pullOneFromVirtualToPlayer(menu, hovered, target, source, mc);
            if (!moved) {
                debug("scrollDown virtual action unavailable for slot {}", hovered.index);
            }
            return moved;
        }

        int sourceBefore = hovered.getItem().getCount();
        int targetBefore = target.getItem().isEmpty() ? 0 : target.getItem().getCount();

        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        click(menu, target, 1, ContainerInput.PICKUP, mc);
        click(menu, hovered, 0, ContainerInput.PICKUP, mc);

        int sourceAfter = hovered.getItem().getCount();
        int targetAfter = target.getItem().isEmpty() ? 0 : target.getItem().getCount();
        if ((sourceAfter == sourceBefore - 1) && (targetAfter == targetBefore + 1 || targetBefore == 0)) {
            debug("scrollDown moved one item from slot {} to player slot {}", hovered.index, target.index);
            return true;
        }

        debug("scrollDown no transfer from slot {} (blocked slot)", hovered.index);
        return false;
        } finally {
            Profiler.get().pop();
        }
    }

    @SubscribeEvent
    static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            clearPendingVirtualPull();
            return;
        }
        if (mc.gameMode == null || mc.player == null) {
            clearPendingVirtualPull();
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();

        if (pendingVirtualPullCleanupTicks > 0) {
            pendingVirtualPullCleanupTicks--;
            if (!menu.getCarried().isEmpty() && hasVirtualSlots(menu, mc.player.getInventory())) {
                if (depositCarriedIntoPlayer(menu, null, null, mc)) {
                    debug("virtual pull recovery deposited carried item");
                }
            }
        }

        if (pendingVirtualPullTicks <= 0) {
            return;
        }
        if (menu.containerId != pendingVirtualPullContainerId) {
            clearPendingVirtualPull();
            return;
        }

        pendingVirtualPullTicks--;
        if (menu.getCarried().isEmpty()) {
            if (pendingVirtualPullTicks <= 0) {
                pendingVirtualPullCleanupTicks = 8;
                clearPendingVirtualPull();
            }
            return;
        }

        Slot preferred = slotByIndex(menu, pendingVirtualPullPreferredPlayerSlot);
        Slot source = slotByIndex(menu, pendingVirtualPullSourceSlot);
        if (!depositCarriedIntoPlayer(menu, source, preferred, mc) && source != null) {
            click(menu, source, 0, ContainerInput.PICKUP, mc);
        }
        pendingVirtualPullCleanupTicks = 8;
        clearPendingVirtualPull();
    }

    private static boolean handleScrollUp(AbstractContainerMenu menu, Slot hovered, boolean hoveredIsPlayer, Minecraft mc) {
        // Reversed by hovered side:
        // player slot: push to opposite inventory
        // non-player slot: pull matching item from player
        return hoveredIsPlayer
                ? sendOneToOppositeInventory(menu, hovered, mc)
                : pullOneFromPlayerIntoSlot(menu, hovered, mc);
    }

    private static boolean handleScrollDown(AbstractContainerMenu menu, Slot hovered, boolean hoveredIsPlayer, Minecraft mc) {
        // Reversed by hovered side:
        // player slot: pull matching item from opposite inventory
        // non-player slot: push item to player inventory
        return hoveredIsPlayer
                ? pullOneMatchingFromOppositeIntoPlayer(menu, hovered, mc)
                : sendOneToPlayerInventory(menu, hovered, mc);
    }

    private static boolean sendOneToOppositeInventory(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        debug("enter sendOneToOppositeInventory");
        Profiler.get().push("auxilio_scroll_up_one_to_container");
        try {
        if (!hovered.hasItem() || !menu.getCarried().isEmpty()) {
            return false;
        }

        // Modded virtual terminal path (AE2-style): route via virtual slot action API.
        if (isPlayerInventorySlot(menu, hovered, mc.player.getInventory()) && hasVirtualSlots(menu, mc.player.getInventory())) {
            boolean virtualMoved = pushOneFromPlayerToVirtual(menu, hovered, mc);
            if (virtualMoved) {
                return true;
            }
        }

        ItemStack source = hovered.getItem();
        Slot target = findSingleItemTarget(menu, hovered, source, mc.player.getInventory());
        if (target == null) {
            debug("scrollUp no opposite target for slot {}", hovered.index);
            return false;
        }

        // For virtual/modded menus, do not fallback to vanilla slot placement.
        if (isVirtualInventorySlot(menu, target, mc.player.getInventory())) {
            debug("scrollUp virtual action unavailable for targetSlot={} from playerSlot={}", target.index, hovered.index);
            return false;
        }

        int sourceBefore = hovered.getItem().getCount();
        int targetBefore = target.getItem().isEmpty() ? 0 : target.getItem().getCount();

        click(menu, hovered, 0, ContainerInput.PICKUP, mc);
        click(menu, target, 1, ContainerInput.PICKUP, mc);
        click(menu, hovered, 0, ContainerInput.PICKUP, mc);

        int sourceAfter = hovered.getItem().getCount();
        int targetAfter = target.getItem().isEmpty() ? 0 : target.getItem().getCount();
        if ((sourceAfter == sourceBefore - 1) && (targetAfter == targetBefore + 1 || targetBefore == 0)) {
            debug("scrollUp moved one item from slot {} to slot {}", hovered.index, target.index);
            return true;
        }

        debug("scrollUp no transfer from slot {} (blocked slot)", hovered.index);
        return false;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean pullOneFromPlayerIntoSlot(AbstractContainerMenu menu, Slot target, Minecraft mc) {
        debug("enter pullOneFromPlayerIntoSlot");
        Profiler.get().push("auxilio_scroll_up_pull_from_player");
        try {
            if (!target.hasItem() || !menu.getCarried().isEmpty()) {
                return false;
            }

            ItemStack wanted = target.getItem().copyWithCount(1);
            Slot source = findPlayerSlotWithType(menu, wanted, mc.player.getInventory());
            if (source == null || source == target) {
                return false;
            }

            // Virtual/modded terminals (AE2-style) must use virtual interaction path.
            if (isVirtualInventorySlot(menu, target, mc.player.getInventory())) {
                boolean moved = pushOneFromPlayerToVirtual(menu, source, mc);
                if (moved) {
                    debug("scrollUp virtual pulled one {} from player slot {}", wanted.getItem(), source.index);
                }
                return moved;
            }

            int before = target.getItem().getCount();
            click(menu, source, 0, ContainerInput.PICKUP, mc);
            click(menu, target, 1, ContainerInput.PICKUP, mc);
            click(menu, source, 0, ContainerInput.PICKUP, mc);
            int after = target.getItem().getCount();
            boolean moved = after == before + 1 && menu.getCarried().isEmpty();
            if (moved) {
                debug("scrollUp pulled one {} from player slot {} into slot {}", wanted.getItem(), source.index, target.index);
            }
            return moved;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean pullOneMatchingFromOppositeIntoPlayer(AbstractContainerMenu menu, Slot playerSlot, Minecraft mc) {
        debug("enter pullOneMatchingFromOppositeIntoPlayer");
        Profiler.get().push("auxilio_scroll_down_pull_from_container");
        try {
            if (!playerSlot.hasItem() || !menu.getCarried().isEmpty()) {
                return false;
            }

            ItemStack wanted = playerSlot.getItem().copyWithCount(1);
            Slot source = null;
            for (Slot slot : menu.slots) {
                if (slot == null || !slot.isActive() || isPlayerInventorySlot(menu, slot, mc.player.getInventory()) || !slot.hasItem()) {
                    continue;
                }
                if (ItemStack.isSameItemSameComponents(slot.getItem(), wanted)) {
                    source = slot;
                    break;
                }
            }
            if (source == null) {
                return false;
            }

            if (isVirtualInventorySlot(menu, source, mc.player.getInventory())) {
                boolean moved = pullOneFromVirtualToPlayer(menu, source, playerSlot, wanted, mc);
                debug("scrollDown pulled matching item via virtual action from slot {} moved={}", source.index, moved);
                return moved;
            }

            int before = playerSlot.getItem().getCount();
            click(menu, source, 0, ContainerInput.PICKUP, mc);
            click(menu, playerSlot, 1, ContainerInput.PICKUP, mc);
            click(menu, source, 0, ContainerInput.PICKUP, mc);
            int after = playerSlot.getItem().getCount();
            boolean moved = after == before + 1 && menu.getCarried().isEmpty();
            if (moved) {
                debug("scrollDown pulled one {} from slot {} into player slot {}", wanted.getItem(), source.index, playerSlot.index);
            }
            return moved;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean sendOneToFurnaceFuelSlot(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        debug("enter sendOneToFurnaceFuelSlot");
        Profiler.get().push("auxilio_shift_scroll_one_to_furnace_fuel");
        try {
            if (!hovered.hasItem() || !menu.getCarried().isEmpty() || menu.slots.size() <= 1) {
                return false;
            }

            Slot fuelSlot = menu.slots.get(1);
            if (fuelSlot == null || !fuelSlot.isActive()) {
                return false;
            }

            ItemStack source = hovered.getItem();
            if (!fuelSlot.mayPlace(source)) {
                return false;
            }

            ItemStack current = fuelSlot.getItem();
            if (!current.isEmpty() && (!ItemStack.isSameItemSameComponents(current, source) || current.getCount() >= fuelSlot.getMaxStackSize(current))) {
                return false;
            }

            click(menu, hovered, 0, ContainerInput.PICKUP, mc);
            click(menu, fuelSlot, 1, ContainerInput.PICKUP, mc);
            click(menu, hovered, 0, ContainerInput.PICKUP, mc);
            debug("shiftScroll moved one item from slot {} to furnace fuel slot {}", hovered.index, fuelSlot.index);
            return true;
        } finally {
            Profiler.get().pop();
        }
    }

    private static Slot findSingleItemTarget(AbstractContainerMenu menu, Slot sourceSlot, ItemStack sourceStack, Inventory playerInventory) {
        debug("enter findSingleItemTarget");
        boolean sourceIsPlayer = isPlayerInventorySlot(menu, sourceSlot, playerInventory);
        Slot emptyCandidate = null;

        for (Slot slot : menu.slots) {
            if (slot == null || slot == sourceSlot || !slot.isActive()) {
                continue;
            }
            if (isPlayerInventorySlot(menu, slot, playerInventory) == sourceIsPlayer) {
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
        debug("enter findSingleItemTargetInPlayerInventory");
        Slot emptyCandidate = null;

        for (Slot slot : menu.slots) {
            if (slot == null || slot == sourceSlot || !slot.isActive() || !isPlayerInventorySlot(menu, slot, playerInventory)) {
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

    private static boolean isPlayerInventorySlot(AbstractContainerMenu menu, Slot slot, Inventory inventory) {
        // Preferred check when slot container identity matches player inventory.
        if (slot.container == inventory) {
            return true;
        }
        // Fallback by index layout for menus that wrap slot containers differently.
        if (menu instanceof InventoryMenu) {
            return slot.index >= 9 && slot.index <= 45;
        }
        if (menu instanceof CraftingMenu) {
            return slot.index >= 10 && slot.index <= 45;
        }
        int playerStart = Math.max(0, menu.slots.size() - 36);
        return slot.index >= playerStart;
    }

    private static boolean sortCraftGrid(AbstractContainerMenu menu, List<Slot> craftSlots, Minecraft mc) {
        debug("enter sortCraftGrid");
        Profiler.get().push("auxilio_sort_craft_grid");
        try {
        List<ItemStack> types = getDistinctTypesInCraftGrid(craftSlots);
        if (types.isEmpty()) {
            return false;
        }

        debug("sortGrid start types={} counts={}", types.size(), craftGridCountsAll(craftSlots));
        if (types.size() == 1) {
            // Single-type mode: spread across all craft slots that can accept this type.
            rebalanceCraftGridForType(menu, craftSlots, types.get(0), true, mc);
        } else {
            // Mixed-type semantics: equalize each type inside its current footprint (slots containing that type).
            // This preserves type groups and avoids cross-type displacement.
            for (ItemStack type : types) {
                rebalanceCraftGridForType(menu, craftSlots, type, false, mc);
            }
        }
        debug("sortGrid end counts={}", craftGridCountsAll(craftSlots));
        return true;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean quickMoveAllOfTypeFromPlayer(AbstractContainerMenu menu, ItemStack sourceType, Minecraft mc) {
        debug("enter quickMoveAllOfTypeFromPlayer");
        Profiler.get().push("auxilio_shift_double_click_quick_move_all");
        try {
        if (sourceType.isEmpty()) {
            return false;
        }

        int moved = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Slot slot : menu.slots) {
                if (slot == null || !slot.isActive() || !slot.hasItem() || !isPlayerInventorySlot(menu, slot, mc.player.getInventory())) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(slot.getItem(), sourceType)) {
                    continue;
                }
                int before = slot.getItem().getCount();
                click(menu, slot, 0, ContainerInput.QUICK_MOVE, mc);
                int after = slot.getItem().getCount();
                if (after < before) {
                    moved += (before - after);
                    changed = true;
                }
            }
        }

        debug("shiftDoubleLeft quickMoveAll type={} moved={}", sourceType.getItem(), moved);
        return moved > 0;
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean rebalanceCraftGridForType(AbstractContainerMenu menu, List<Slot> craftSlots, ItemStack type, boolean includeEmptySlots, Minecraft mc) {
        debug("enter rebalanceCraftGridForType");
        List<Slot> eligibleSlots = new ArrayList<>();
        for (Slot slot : craftSlots) {
            if (!slot.isActive() || !slot.mayPlace(type)) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (ItemStack.isSameItemSameComponents(current, type) || (includeEmptySlots && current.isEmpty())) {
                eligibleSlots.add(slot);
            }
        }

        if (eligibleSlots.isEmpty()) {
            return false;
        }

        int total = 0;
        for (Slot slot : eligibleSlots) {
            ItemStack current = slot.getItem();
            if (ItemStack.isSameItemSameComponents(current, type)) {
                total += current.getCount();
            }
        }
        if (total <= 0) {
            return false;
        }

        int slotCount = eligibleSlots.size();
        int base = total / slotCount;
        int remainder = total % slotCount;

        List<Integer> beforeCounts = craftGridCounts(craftSlots, type);
        List<Slot> donors = new ArrayList<>();
        List<Slot> receivers = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = eligibleSlots.get(i);
            ItemStack current = slot.getItem();
            int count = !current.isEmpty() && ItemStack.isSameItemSameComponents(current, type) ? current.getCount() : 0;
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

        // Keeps redistribution deterministic across repeated presses.
        donors.sort(Comparator.comparingInt(s -> s.index));
        receivers.sort(Comparator.comparingInt(s -> s.index));

        int moves = Math.min(donors.size(), receivers.size());
        for (int i = 0; i < moves; i++) {
            moveSingleItem(menu, donors.get(i), receivers.get(i), mc);
        }

        debug("rebalance type={} includeEmpty={} total={} base={} rem={} moves={} before/after={}/{}",
                type.getItem(), includeEmptySlots, total, base, remainder, moves, beforeCounts, craftGridCounts(craftSlots, type));
        return true;
    }

    private static List<Slot> getCraftSlots(AbstractContainerMenu menu) {
        return CraftGridResolver.resolve(menu);
    }

    private static boolean isSpreadSupportedMenu(AbstractContainerMenu menu) {
        debug("enter isSpreadSupportedMenu");
        return !getCraftSlots(menu).isEmpty();
    }

    private static boolean isCraftSlot(AbstractContainerMenu menu, Slot slot) {
        debug("enter isCraftSlot");
        return getCraftSlots(menu).contains(slot);
    }

    private static List<ItemStack> getDistinctTypesInCraftGrid(List<Slot> craftSlots) {
        debug("enter getDistinctTypesInCraftGrid");
        List<ItemStack> types = new ArrayList<>();
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            if (current.isEmpty()) {
                continue;
            }
            boolean seen = false;
            for (ItemStack type : types) {
                if (ItemStack.isSameItemSameComponents(type, current)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                types.add(current.copyWithCount(1));
            }
        }
        return types;
    }

    private static void moveSingleItem(AbstractContainerMenu menu, Slot from, Slot to, Minecraft mc) {
        debug("enter moveSingleItem");
        if (from == to || !from.hasItem()) {
            return;
        }

        // Pickup full stack, place one with right click, then return the remainder.
        click(menu, from, 0, ContainerInput.PICKUP, mc);
        click(menu, to, 1, ContainerInput.PICKUP, mc);
        click(menu, from, 0, ContainerInput.PICKUP, mc);
    }

    private static boolean matchesSpreadMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        InputConstants.Key pressed = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
        // isActiveAndMatches handles vanilla keybind behavior; direct key compare keeps Shift+bind working.
        return SPREAD_IN_CRAFTING.isActiveAndMatches(pressed) || SPREAD_IN_CRAFTING.getKey().equals(pressed);
    }

    private static boolean matchesSpreadKey(ScreenEvent.KeyPressed.Pre event) {
        InputConstants.Key pressed = InputConstants.getKey(event.getKeyEvent());
        return SPREAD_IN_CRAFTING.isActiveAndMatches(pressed) || SPREAD_IN_CRAFTING.getKey().equals(pressed);
    }

    private static boolean isKeyFeatureEnabled(KeyMapping key) {
        return !key.isUnbound();
    }

    private static void click(AbstractContainerMenu menu, Slot slot, int button, ContainerInput input, Minecraft mc) {
        clickRaw(menu.containerId, slot.index, button, input, mc);
    }

    private static void clickRaw(int containerId, int slotIndex, int button, ContainerInput input, Minecraft mc) {
        mc.gameMode.handleContainerInput(containerId, slotIndex, button, input, mc.player);
    }

    private static boolean isShiftHeld(Minecraft mc) {
        return InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean isVirtualInventorySlot(AbstractContainerMenu menu, Slot slot, Inventory playerInventory) {
        if (isPlayerInventorySlot(menu, slot, playerInventory)) {
            return false;
        }
        String className = slot.getClass().getName();
        return className.contains(".menu.slot.") || className.contains("RepoSlot");
    }

    private static boolean hasVirtualSlots(AbstractContainerMenu menu, Inventory playerInventory) {
        for (Slot slot : menu.slots) {
            if (slot != null && isVirtualInventorySlot(menu, slot, playerInventory)) {
                return true;
            }
        }
        return false;
    }

    private static Slot findMatchingVirtualSlot(AbstractContainerMenu menu, ItemStack reference, Inventory playerInventory) {
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.isActive() || !isVirtualInventorySlot(menu, slot, playerInventory) || !slot.hasItem()) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(slot.getItem(), reference)) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findAnyVirtualSlot(AbstractContainerMenu menu, Inventory playerInventory) {
        for (Slot slot : menu.slots) {
            if (slot != null && slot.isActive() && isVirtualInventorySlot(menu, slot, playerInventory)) {
                return slot;
            }
        }
        return null;
    }

    private static boolean pushOneFromPlayerToVirtual(AbstractContainerMenu menu, Slot playerSource, Minecraft mc) {
        ItemStack sourceType = playerSource.getItem().copyWithCount(1);
        Slot virtualSlot = findAnyVirtualSlot(menu, mc.player.getInventory());
        if (virtualSlot == null) {
            debug("scrollUp virtual push no virtual slot for {}", sourceType.getItem());
            return false;
        }

        if (!menu.getCarried().isEmpty()) {
            debug("scrollUp virtual push skipped (carried not empty)");
            return false;
        }

        int before = playerSource.getItem().getCount();
        // Provide carried stack explicitly, let virtual action consume one, then return remainder.
        click(menu, playerSource, 0, ContainerInput.PICKUP, mc);
        if (menu.getCarried().isEmpty()) {
            debug("scrollUp virtual push pickup failed playerSlot={}", playerSource.index);
            return false;
        }
        boolean handled = tryVirtualInventoryAction(menu, virtualSlot, "SPLIT_OR_PLACE_SINGLE", -1L)
                || tryVirtualInventoryAction(menu, virtualSlot, "ROLL_DOWN", -1L)
                || tryVirtualInventoryAction(menu, virtualSlot, "PICKUP_OR_SET_DOWN", -1L);
        if (!handled) {
            click(menu, playerSource, 0, ContainerInput.PICKUP, mc);
            debug("scrollUp virtual push action unavailable virtualSlot={} playerSlot={}", virtualSlot.index, playerSource.index);
            return false;
        }
        click(menu, playerSource, 0, ContainerInput.PICKUP, mc);
        int after = playerSource.getItem().getCount();
        boolean moved = after == before - 1 && menu.getCarried().isEmpty();
        debug("scrollUp virtual push from playerSlot={} via virtualSlot={} moved={} count {}->{}", playerSource.index, virtualSlot.index, moved, before, after);
        return moved;
    }

    private static boolean pullOneFromVirtualToPlayer(AbstractContainerMenu menu, Slot virtualSource, Slot playerTarget, ItemStack reference, Minecraft mc) {
        if (pendingVirtualPullTicks > 0) {
            return false;
        }
        boolean handled = tryVirtualInventoryAction(menu, virtualSource, "PICKUP_SINGLE", null);
        if (!handled) {
            return false;
        }
        pendingVirtualPullContainerId = menu.containerId;
        pendingVirtualPullSourceSlot = virtualSource.index;
        pendingVirtualPullPreferredPlayerSlot = playerTarget == null ? -1 : playerTarget.index;
        pendingVirtualPullTicks = 6;
        debug("scrollDown virtual action queued slot={} action=PICKUP_SINGLE", virtualSource.index);
        return true;
    }

    private static int countItemInPlayerInventory(AbstractContainerMenu menu, Inventory inventory, ItemStack reference) {
        int total = 0;
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.hasItem() || !isPlayerInventorySlot(menu, slot, inventory)) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(slot.getItem(), reference)) {
                total += slot.getItem().getCount();
            }
        }
        return total;
    }

    private static boolean depositCarriedIntoPlayer(AbstractContainerMenu menu, Slot virtualSource, Slot preferredTarget, Minecraft mc) {
        if (menu.getCarried().isEmpty()) {
            return true;
        }

        ItemStack one = menu.getCarried().copyWithCount(1);
        Slot target = findSingleItemTargetInPlayerInventory(menu, virtualSource, one, mc.player.getInventory());
        if (target == null) {
            target = preferredTarget;
        }

        if (target != null) {
            click(menu, target, 1, ContainerInput.PICKUP, mc);
            if (menu.getCarried().isEmpty()) {
                return true;
            }
        }

        // Fallback sweep across player slots in case preferred target is blocked.
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.isActive() || !isPlayerInventorySlot(menu, slot, mc.player.getInventory())) {
                continue;
            }
            if (!slot.mayPlace(menu.getCarried())) {
                continue;
            }
            click(menu, slot, 1, ContainerInput.PICKUP, mc);
            if (menu.getCarried().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static Slot slotByIndex(AbstractContainerMenu menu, int index) {
        if (index < 0) {
            return null;
        }
        for (Slot slot : menu.slots) {
            if (slot != null && slot.index == index) {
                return slot;
            }
        }
        return null;
    }

    private static void clearPendingVirtualPull() {
        pendingVirtualPullContainerId = -1;
        pendingVirtualPullSourceSlot = -1;
        pendingVirtualPullPreferredPlayerSlot = -1;
        pendingVirtualPullTicks = 0;
    }

    private static boolean tryVirtualInventoryAction(AbstractContainerMenu menu, Slot hovered, String actionName, Long serialOverride) {
        try {
            long serial;
            if (serialOverride != null) {
                serial = serialOverride;
            } else {
                Method getEntry = hovered.getClass().getMethod("getEntry");
                Object entry = getEntry.invoke(hovered);
                if (entry == null) {
                    return false;
                }
                Method getSerial = entry.getClass().getMethod("getSerial");
                serial = (long) getSerial.invoke(entry);
            }

            Class<?> inventoryActionClass = Class.forName("appeng.helpers.InventoryAction");
            Object action = Enum.valueOf((Class<Enum>) inventoryActionClass.asSubclass(Enum.class), actionName);

            Method handleInteraction = menu.getClass().getMethod("handleInteraction", long.class, inventoryActionClass);
            handleInteraction.invoke(menu, serial, action);
            return true;
        } catch (Exception e) {
            debug("virtual action send failed action={} err={}", actionName, e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean canIncrementSameStack(Slot slot, ItemStack carried) {
        debug("enter canIncrementSameStack");
        if (!slot.isActive() || !slot.mayPlace(carried)) {
            return false;
        }
        ItemStack current = slot.getItem();
        if (current.isEmpty()) {
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(current, carried)) {
            return false;
        }
        return current.getCount() < slot.getMaxStackSize(current);
    }

    private static List<Integer> craftGridCounts(List<Slot> craftSlots, ItemStack reference) {
        debug("enter craftGridCounts");
        List<Integer> counts = new ArrayList<>(craftSlots.size());
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            counts.add(!current.isEmpty() && ItemStack.isSameItemSameComponents(current, reference) ? current.getCount() : 0);
        }
        return counts;
    }

    private static List<String> craftGridCountsAll(List<Slot> craftSlots) {
        debug("enter craftGridCountsAll");
        List<String> out = new ArrayList<>(craftSlots.size());
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            out.add(current.isEmpty() ? "empty" : current.getItem() + "x" + current.getCount());
        }
        return out;
    }

    private static Slot findPlayerSlotWithType(AbstractContainerMenu menu, ItemStack type, Inventory inventory) {
        debug("enter findPlayerSlotWithType");
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.isActive() || !isPlayerInventorySlot(menu, slot, inventory) || !slot.hasItem()) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(slot.getItem(), type)) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findSingleItemTargetInCraftGrid(List<Slot> craftSlots, ItemStack sourceStack) {
        debug("enter findSingleItemTargetInCraftGrid");
        Slot emptyCandidate = null;
        for (Slot slot : craftSlots) {
            if (!slot.isActive() || !slot.mayPlace(sourceStack)) {
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

    private static void debug(String message, Object... args) {
        AuxilioDebug.log("MouseTweaks", message, args);
    }
}
