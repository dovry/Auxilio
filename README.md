master ![build status](https://github.com/dovry/Auxillio/actions/workflows/build.yml/badge.svg?branch=master) dev ![build status](https://github.com/dovry/Auxillio/actions/workflows/build.yml/badge.svg?branch=dev)

Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/

Auxillio Features (Current)
==========
This mod currently focuses on mouse/item interaction quality-of-life in inventory and crafting screens.

- Middle click sort in crafting grid:
  - Middle click on any crafting slot (2x2 or 3x3) sorts the grid.
    - Single-item-type grids are spread as evenly as possible across all valid crafting slots.
    
		![single type slots](images/item_spread.mp4 "Single item type spread")
    
	- Mixed-item grids use type-local balancing: each type is equalized within the slots that already contain that type.
	
		![mixed slots](images/mixed_items.mp4 "Mixed item type spread")
		
		![mixed slots 2](images/mixed_items.mp4 "Mixed item type spread 2")

- Shift + middle click deposit:
  - Shift + middle click on an inventory slot pushes all items of that same type from player inventory into the crafting grid (as space allows), then equalizes that type in the grid.

- Shift + drag quick move:
  - Hold Shift and drag left-click across slots to quick-move hovered stacks.
  - Each hovered slot is clicked at most once per drag stroke to avoid repeated click spam.

- Scroll transfer:
  - Scroll up: move 1 item from hovered slot toward the opposite inventory side.
  - Scroll down: move 1 item from hovered non-player slot into player inventory.

- Debug logging:
  - Config option: `debugMouseTweaks`.
  - When enabled, detailed mouse tweak traces are logged with `[MouseTweaks]` in `run/logs/latest.log`.
