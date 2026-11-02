# Integrations for NeoForge 1.21.1 mods

**Rule: a mod must work fully by itself first.** Integrations with other mods are optional add-ons. They may only
add behavior; they must never replace or restrict what the mod does on its own. Every mod's CI includes a run with
no optional integrations installed.

## Which mods packs actually install

Evidence, gathered 2026-11-01 from six real Minecraft 1.21.1 NeoForge modpacks (installed CurseForge instances):
All the Mods 10, All the Mods 10 To the Sky, Ascending Fishers, FTB OceanBlock 2, FTB Presents Direwolf20 Season 14,
FTB StoneBlock 4. Their sizes range from 139 to 448 mods.

| Tier | Present in | Mods |
|---|---|---|
| **1** | all 6 packs | JEI, Curios, Jade, Patchouli, KubeJS, Sophisticated Backpacks, Applied Energistics 2, Mekanism, AppleSkin, Mouse Tweaks |
| **2** | 5 of 6 | Create, Refined Storage, FTB Quests, Modonomicon, Pipez |
| **3** | 2 of 6 | Configured, Polymorph, Jade Addons |
| none | 0 of 6 | EMI, REI, CraftTweaker, Accessories, The One Probe, WTHIT, Storage Drawers, Traveler's Backpack |

CurseForge all-time downloads agree on the top: JEI is the most downloaded mod overall (642.6M), Curios is #13,
Patchouli #15, Jade #18. All-time totals favor older mods, so the pack data above is the better guide for 1.21.1.

## What integrating means, by kind

| Kind | Mods | What to do | Needed when |
|---|---|---|---|
| Recipe viewer | **JEI** | Vanilla recipe types show automatically. Custom recipe types (special or dynamic recipes) need a JEI plugin, or players cannot see them. Add info pages for mechanics that recipes do not explain. | Every mod with items |
| Recipe editing (pack makers) | **KubeJS**, CraftTweaker | Mostly free: keep recipes as data files, use common `c:` tags (`c:ingots/gold`), no hard-coded recipe logic. Pack makers then make items harder or easier to obtain. CraftTweaker is in none of the six packs above but is still used by pack makers. Prove it with a test run that loads CraftTweaker and a sample script (see angelring-pb `-Pcrafttweaker`). | Every mod with recipes |
| Accessory slots | **Curios** | Add the item to a slot tag (`data/curios/tags/item/<slot>.json`) and enable the slot for players (`data/<modid>/curios/entities/*.json`). The item must still work without Curios. Keep Curios code in one class loaded only when Curios is present. | Wearable items |
| Block info overlay | **Jade** | Register a Jade plugin to show block or entity state when looked at. Jade 15.10.6 for 1.21.1 NeoForge: `maven.modrinth:jade:15.10.6+neoforge`. | Mods with blocks, machines or entities that have state; not items alone |
| Guidebook | **Patchouli**, Modonomicon | Ship an in-game book describing mechanics. | Mods with non-obvious mechanics |
| Backpacks and storage | **Sophisticated Backpacks**, AE2, Refined Storage, Mekanism | Use NeoForge capabilities (`Capabilities.ItemHandler.ITEM` for items) instead of special cases, so any mod exposing an item handler works. Decide explicitly whether items inside a backpack count (for example, a ring inside a backpack). | Mods that pull items from the inventory or check carried items |
| Quests | FTB Quests | Keep item and block ids stable between versions; packs reference them. | Every mod |
| Config screen | NeoForge built-in, Configured | Register NeoForge's config screen so settings are editable in game. | Mods with client or common configs |

Also present in nearly every pack but not integration targets: GeckoLib, Cloth Config, Architectury, Balm, Placebo,
Mouse Tweaks, AppleSkin, map mods. They matter only for compatibility: the mod must not crash or conflict when they
are installed.

## How to test

1. **Standalone run (required):** no optional integrations on the classpath. This is the configuration every
   player has.
2. **Integration runs**, adding the optional mods the mod supports. Pattern: optional mods as `localRuntime` for
   development, and Gradle properties to choose the test setup:

   ```groovy
   def standalone = project.hasProperty('standalone')     // no optional mods
   def crafttweaker = project.hasProperty('crafttweaker') // plus CraftTweaker and a sample script
   dependencies {
       if(!standalone) {
           gametestRuntimeOnly "top.theillusivec4.curios:curios-neoforge:${curios_version}"
           gametestRuntimeOnly "mezz.jei:jei-${minecraft_version}-neoforge:${jei_version}"
       }
   }
   ```

   Tests read presence with `ModList.get().isLoaded("curios")` and keep expectations that hold in every setup.
   See [angelring-pb](https://github.com/peterbax117/angelring-pb) for a CI matrix (`standalone`,
   `integrations`, `crafttweaker`). Run integrations separately when they could interfere with each other.
3. **Before a release, a common-pack smoke run** with the tier 1 mods that have no heavy dependencies (JEI,
   Curios, Jade, Patchouli, Sophisticated Backpacks) to catch load-order or conflict crashes.

Write the expected behavior as a table before coding: integration present or absent, by every place the item or
feature can be used. Confirm every "does not work" cell with the owner; never encode an unconfirmed negative
behavior in a test.

## Deciding per mod

Not every integration applies to every mod. For each mod, write a table of integrations with "applies or not" and
the exact behavior, and confirm it with the owner before coding. Angel Ring (confirmed 2026-11-01):

| Integration | Applies | Behavior |
|---|---|---|
| JEI | Yes | Information page: what the ring does, where it works, double-tap jump |
| Curios | Yes | Ring slot is one more place to wear it |
| CraftTweaker, KubeJS | Yes | Pack makers can replace or remove the recipe |
| Backpacks, bundles, shulker boxes | No | A ring stored inside one does not grant flight |
| Jade | No | Nothing to look at: the ring is an item, not a block |
| FTB Quests | Passively | Item id `angelring_pb:angel_ring` stays stable |
| Patchouli, AE2, Mekanism, config screen | No | Simple mechanic, no blocks, no settings |

## Status of our mods

| Mod | Standalone | Integrations |
|---|---|---|
| [Construction Wand port](https://github.com/peterbax117/ConstructionWand/tree/port/1.21.1) | Tested (CI) | JEI plugin (info pages); item handler capability for containers. Botania removed (no 1.21.1 release). Not yet: Jade, config screen, Sophisticated Backpacks test |
| [Angel Ring](https://github.com/peterbax117/angelring-pb) | Tested (CI) | Curios ring slot, JEI info page, CraftTweaker recipe replacement; rings inside containers do not count. All tested (CI matrix: standalone, integrations, crafttweaker) |
