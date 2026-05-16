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

    public AuxilioClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(AuxilioClient::registerKeyMappings);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        Auxilio.LOGGER.info("HELLO FROM CLIENT SETUP");
        Auxilio.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        Auxilio.LOGGER.info("MouseTweaks debug toggle (debugMouseTweaks) = {}", Config.DEBUG_MOUSE_TWEAKS.getAsBoolean());
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
        } else if ((menu instanceof InventoryMenu || menu instanceof CraftingMenu)
                && menu.getCarried().isEmpty()
                && Config.ENABLE_SPREAD_SORT.getAsBoolean()
                && isKeyFeatureEnabled(SPREAD_IN_CRAFTING)
                && matchesSpreadMouse(event)
                && isCraftSlot(menu, sourceSlot)) {
            handled = sortCraftGrid(menu, mc);
        }

        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
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
        if (!(menu instanceof InventoryMenu) && !(menu instanceof CraftingMenu)) {
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            return;
        }

        Slot sourceSlot = screen.getHoveredSlot();
        if (sourceSlot == null) {
            return;
        }

        boolean handled = false;
        if (isCraftSlot(menu, sourceSlot)) {
            handled = sortCraftGrid(menu, mc);
        }

        if (handled) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
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
        Profiler.get().push("auxilio_scroll_down_one_to_player");
        try {
        if (!hovered.hasItem() || !menu.getCarried().isEmpty() || isPlayerInventorySlot(menu, hovered, mc.player.getInventory())) {
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
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean sendOneToOppositeInventory(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
        Profiler.get().push("auxilio_scroll_up_one_to_container");
        try {
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
        } finally {
            Profiler.get().pop();
        }
    }

    private static boolean sendOneToFurnaceFuelSlot(AbstractContainerMenu menu, Slot hovered, Minecraft mc) {
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

    private static boolean sortCraftGrid(AbstractContainerMenu menu, Minecraft mc) {
        Profiler.get().push("auxilio_sort_craft_grid");
        try {
        List<Slot> craftSlots = getCraftSlots(menu);
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
        return menu instanceof InventoryMenu ? ((InventoryMenu) menu).getInputGridSlots() : ((CraftingMenu) menu).getInputGridSlots();
    }

    private static boolean isCraftSlot(AbstractContainerMenu menu, Slot slot) {
        return getCraftSlots(menu).contains(slot);
    }

    private static List<ItemStack> getDistinctTypesInCraftGrid(List<Slot> craftSlots) {
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

    private static boolean canIncrementSameStack(Slot slot, ItemStack carried) {
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
        List<Integer> counts = new ArrayList<>(craftSlots.size());
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            counts.add(!current.isEmpty() && ItemStack.isSameItemSameComponents(current, reference) ? current.getCount() : 0);
        }
        return counts;
    }

    private static List<String> craftGridCountsAll(List<Slot> craftSlots) {
        List<String> out = new ArrayList<>(craftSlots.size());
        for (Slot slot : craftSlots) {
            ItemStack current = slot.getItem();
            out.add(current.isEmpty() ? "empty" : current.getItem() + "x" + current.getCount());
        }
        return out;
    }

    private static Slot findPlayerSlotWithType(AbstractContainerMenu menu, ItemStack type, Inventory inventory) {
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
        if (Config.DEBUG_MOUSE_TWEAKS.getAsBoolean()) {
            Auxilio.LOGGER.info("[MouseTweaks] " + message, args);
        }
    }
}
