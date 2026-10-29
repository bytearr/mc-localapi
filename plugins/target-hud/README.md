# Target HUD (Minecraft 26.3)

Standalone Local API plugin. Put `localapi-target-hud-1.0.1.jar` in
`config/localapi/plugins/`, approve it in Local API → Plugins and restart the client.
No core replacement, installer edit, mixin, additional mod dependency or configuration file.

The panel is horizontally centered, with its center at one fifth of the scaled GUI height
(slightly above its original quarter-height position). It shows the live block/entity display name and, below it in darker gray, the registry ID.
Blocks show their vanilla item icon when one exists. In survival, a green check or red X beside it represents vanilla `Player.hasCorrectToolForDrops`: bare hand on dirt
is suitable, bare hand on stone is not. This is the tool requirement, not a prediction of a
block's loot table, game rules, Silk Touch or server modifications. Unbreakable blocks are
marked with an X. Creative and spectator omit the check/X and progress bar.

During local mining, a slim bar shows the actual `destroyProgress`. It only belongs to the
current target and held stack. Read-only MethodHandles access the private 26.3 fields; no
core accessor or mixin is injected. An incompatible field layout fails plugin loading rather
than fabricating progress. Nothing is drawn for a miss, air, unloaded chunk, removed entity,
F1, pause or any open screen. No retained target state, polling, network calls or worker.

Names use the client components; registry IDs are language-independent. No tool-status text. Living entities additionally show their live current/max health in HP
(2 HP = one vanilla heart) and armor points beside heart/shield symbols. Non-living entities
have no fabricated health or armor values.
Check/X strokes are drawn directly and do not require font glyphs. Blocks without an item icon
keep the check/X without inventing an item. Long names wrap to at most two lines. The panel stays above the crosshair
and is omitted if the scaled GUI is too small to fit it safely.

Build with `build.ps1` after compiling the core. Core classes and Minecraft/Fabric dependencies
are compile-only and are not included in this plugin jar. `install.ps1` is not used.

Validation: compiled with Java 25 against Minecraft 26.3. Headless checks with real vanilla
block/tool logic and mocked client/render surfaces cover visibility, creative, live progress,
target/tool switches, loaded-chunk guards and centering at three scaled GUI sizes. The actual
Fabric HUD integration and GPU rendering still need an ingame check.
