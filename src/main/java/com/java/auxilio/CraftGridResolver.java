package com.java.auxilio;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class CraftGridResolver {
    private CraftGridResolver() {
    }

    static List<Slot> resolve(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu inventoryMenu) {
            return inventoryMenu.getInputGridSlots();
        }
        if (menu instanceof CraftingMenu craftingMenu) {
            return craftingMenu.getInputGridSlots();
        }

        List<Slot> byCraftingMatrix = resolveViaCraftingMatrixProvider(menu);
        if (!byCraftingMatrix.isEmpty()) {
            return byCraftingMatrix;
        }

        return resolveViaCraftingSlotHeuristics(menu);
    }

    private static List<Slot> resolveViaCraftingMatrixProvider(AbstractContainerMenu menu) {
        try {
            Method getCraftingMatrix = menu.getClass().getMethod("getCraftingMatrix");
            Object matrix = getCraftingMatrix.invoke(menu);
            if (matrix == null) {
                return List.of();
            }

            Method toContainer = matrix.getClass().getMethod("toContainer");
            Object matrixContainer = toContainer.invoke(matrix);
            if (!(matrixContainer instanceof net.minecraft.world.Container container)) {
                return List.of();
            }

            List<Slot> out = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (slot != null && slot.container == container) {
                    out.add(slot);
                }
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Slot> resolveViaCraftingSlotHeuristics(AbstractContainerMenu menu) {
        List<Slot> candidates = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot == null || !slot.isActive()) {
                continue;
            }
            String simpleName = slot.getClass().getSimpleName();
            if (simpleName.contains("CraftingMatrixSlot") || simpleName.contains("CraftMatrixSlot")) {
                candidates.add(slot);
            }
        }
        return candidates;
    }
}
