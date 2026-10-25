# AutoMine Testing

Dev-only Minecraft client helper for UI and worldgen smoke checks.

## Loaders

Built for **both** Fabric and NeoForge:

| Project | Jar | Entry point |
|---|---|---|
| `fabric/` | `automine_testing-fabric-1.21.1-*.jar` | `AutoMineTestingFabric` (+ the `/automine` client commands) |
| `neoforge/` | `automine_testing-neoforge-1.21.1-*.jar` | `AutoMineTestingNeoForge` |

`common/src/main/java` holds the loader-neutral implementation that touches only
vanilla classes. It is **not** a Gradle subproject: each loader project adds it
as an extra source directory and compiles it against its own Minecraft jar,
which avoids needing a third vanilla-only toolchain.

```powershell
.\gradlew.bat :neoforge:build      # or :fabric:build, or collectJars for both
```

The NeoForge target exists because Synesthesia is NeoForge and ships **Forgified Fabric API**
(nested in `txnilib`), *not* Sinytra Connector. FFAPI provides Fabric API surfaces; it does not
load `fabric.mod.json` mods. A Fabric jar dropped into that pack is never mentioned in the log
again - it simply does not load. `install-into-prism-instance.ps1` now takes `-Loader` and
refuses the combination that fails silently.

The `/automine` client commands are Fabric-only: they are written against Fabric's
`ClientCommandManager` and `FabricClientCommandSource`. The HTTP server exposes the same actions
on both loaders, and that is what drives automation.

## Running two instances at once

The HTTP server binds `127.0.0.1:47321` by default, and **whichever client starts first wins the
socket**. The loser logs `Failed to start AutoMine HTTP server` and every probe goes to the other
instance, looking completely normal. Override it:

```text
-Dautomine.httpPort=47322
```

The tell that a response came from the wrong game is `commandFile` in the status JSON - it names
the instance directory that answered.

The mod watches this file in the active Minecraft game directory:

```text
automine_testing/command.json
```

Example:

```json
{
  "action": "open_inventory_and_screenshot",
  "screenshot": "inventory.png",
  "delayTicks": 3
}
```

Screenshots are written to the normal Minecraft `screenshots/` directory.

Auto-open and auto-create are disabled by default. Opt in with JVM system
properties when a dedicated test instance should enter a save after the title
screen appears. With both switches enabled, a missing save is created as a
normal creative-mode world with commands enabled:

```text
-Dautomine.autoOpenWorld=true
-Dautomine.worldName="New World"
-Dautomine.createWorldIfMissing=true
```

Useful actions:

- `status`
- `open_world`
- `close_world`
- `ami_quest_status`
- `ami_search`
- `ami_reindex`
- `ami_reindex_and_search`
- `run_command`
- `run_chat`
- `entity_snapshot`
- `open_atelier_codex`
- `open_screen`
- `screen_info`
- `screen_targets`
- `click_screen`
- `click_screen_target`
- `press_key`
- `open_inventory`
- `close_screen`
- `screenshot`
- `open_roadweaver_map_and_screenshot`
- `resample_roadweaver_map_and_screenshot`
- `open_screen_and_screenshot`
- `click_screen_and_screenshot`
- `click_screen_target_and_screenshot`
- `press_key_and_screenshot`
- `open_inventory_and_screenshot`
- `create_world` (optional deterministic `seed`)

AutoMine also starts a loopback HTTP server on `localhost:47321`:

```powershell
.\scripts\invoke-automine-http.ps1 -Action status
.\scripts\invoke-automine-http.ps1 -Action inventory_screenshot -Screenshot inventory.png
.\scripts\invoke-automine-http.ps1 -Action screen_info
.\scripts\invoke-automine-http.ps1 -Action screen_click -X 332 -Y 233 -Button 0 -Screenshot click.png
.\scripts\invoke-automine-http.ps1 -Action command -Command 'time set day'
.\scripts\invoke-automine-http.ps1 -Action entity_snapshot -Categories monster -Radius 128
.\scripts\invoke-automine-http.ps1 -Action ami_search -Query '@tconstruct' -Lens MODULAR_GEAR -Limit 50
.\scripts\invoke-automine-http.ps1 -Action ami_reindex_and_search -Query '@tconstruct' -Lens MODULAR_GEAR -TimeoutMs 180000
```

For a Prism instance, build and deploy the jar that matches its loader:

```powershell
.\scripts\install-into-prism-instance.ps1 -InstanceName "Mod Test" -Loader fabric
.\scripts\install-into-prism-instance.ps1 -InstanceName "Synesthesia" -Loader neoforge
```

Launch Prism and wait for AutoMine with one blocking helper. Always pass the
Prism data root explicitly; this avoids routing the launch request to another
running Prism root. Supplying `-World` waits until the receiving-level screen
has cleared and the world is ready for interaction, then prints one JSON result:

```powershell
.\scripts\start-prism-instance-and-wait.ps1 `
  -InstanceName "Synesthesia 2" `
  -World "New World (1)"
```

Do not probe the Windows GUI executable with `prismlauncher.exe --help`; it can
start a persistent launcher process rather than returning console help. The
helper uses `-d <Prism root> -l <instance folder>` and verifies AutoMine's
`commandFile` before it reports readiness.

The installer rejects an obvious loader mismatch. After launch, run `-Action
status` and confirm that `commandFile` points into the intended instance before
doing anything stateful. This catches the case where another client already owns
port 47321. Installation also removes older AutoMine versions or a jar for the
other loader from the target `mods` directory before copying the selected build.
When a temporary test dependency should be removed, close Minecraft first, then
remove only AutoMine's jars from that instance:

```powershell
.\scripts\remove-from-prism-instance.ps1 -InstanceName "Synesthesia" -WhatIf
.\scripts\remove-from-prism-instance.ps1 -InstanceName "Synesthesia"
```

Create a deterministic fresh world, then capture RoadWeaver's map with a
zoomed-out screenshot. `world_create` waits for the title screen before
starting the create flow, then waits until the client is actually in-world or
`-WorldTimeoutMs` expires. `worldLoadElapsedMs` in the response measures the
time from the create request until the client enters the world.

```powershell
.\scripts\invoke-automine-http.ps1 -Action world_create -World "OTG Benchmark 12345" -Seed 12345
.\scripts\invoke-automine-http.ps1 -Action roadweaver_map_screenshot `
  -Screenshot otg-12345-map.png -ZoomOutSteps 8 -DelayTicks 80
```

Use `-WorldPreset` when a test must start with a registered world preset. For
example, Terra registers `terra:kanto/kanto` after the `kanto:kanto` config
pack has loaded:

```powershell
.\scripts\invoke-automine-http.ps1 `
  -Action world_create `
  -World "Kanto League Smoke Test" `
  -WorldPreset "terra:kanto/kanto"
```

Open an existing save with `world_open` instead of recreating it. The request
now remains open until the receiving-level screen has cleared or the world
timeout expires:

```powershell
.\scripts\invoke-automine-http.ps1 -Action world_open -World "Tectonic Skies"
```

Close or quit a controlled client without menu navigation:

```powershell
.\scripts\invoke-automine-http.ps1 -Action world_close
.\scripts\invoke-automine-http.ps1 -Action client_quit
```

For spawn and mob-cap checks, use `entity_snapshot` instead of chat-log
scraping. It reads the integrated server world on the client thread, anchors the
query at the local player, and returns counts by entity type and mob category
plus optional per-entity positions:

```powershell
.\scripts\invoke-automine-http.ps1 `
  -Action entity_snapshot `
  -Categories monster `
  -Radius 128

.\scripts\invoke-automine-http.ps1 `
  -Action entity_snapshot `
  -Types minecraft:enderman,endermanoverhaul:cave_enderman `
  -Radius 128 `
  -MaxResults 2000
```

Use `-IncludePlayers` when player entities should be included in the returned
rows. `-Types` matches entity type ids exactly, `-Categories` matches vanilla mob
category names such as `monster`, and `-NameContains` is a case-insensitive
display-name filter.

Recipe viewers commonly finish in-world recipe registration only after the
client joins a world. Enter a save before checking JEI or EMI recipe-generation
errors, then inspect the instance's `logs/latest.log`.

For a low-touch benchmark, use the single orchestrator script. It launches the
configured Prism instance by default, waits silently for the local helper,
creates the specified seed, waits for RoadWeaver's initial plan, runs the
enabled biome and terrain audits, resamples and captures the RoadWeaver
overview, and prints one JSON result when the artifacts are ready. Pass
`-Launch:$false` only when attaching to a client that is already running:

```powershell
.\scripts\run-worldgen-benchmark.ps1 -World "OTG Benchmark 12345" -Seed 12345
```

The loopback HTTP API includes:

- `GET /status` (also `GET /automine/status`)
- `POST /world/open`
- `POST /world/create`
- `POST /world/close`
- `POST /client/quit`
- `POST /entities/snapshot`
- `POST /roadweaver/map/screenshot`
- `POST /roadweaver/map/resample-screenshot`
- `POST /worldgen/biome-audit`
- `POST /worldgen/terrain-audit`
- `GET /worldgen/terrain-audit/status`
- `POST /worldgen/chunk-generation-audit`
- `POST /inventory/open`
- `POST /inventory/screenshot`
- `POST /screen/open`
- `GET /screen/info`
- `GET /screen/targets`
- `POST /screen/click`
- `POST /screen/click-target`
- `POST /screen/key`
- `POST /screen/close`
- `POST /screenshot`
- `POST /command`
- `POST /chat`
- `POST /world/interact-block`
- `GET /ami/status`
- `POST /ami/quest-status`
- `POST /ami/search`
- `POST /ami/reindex`
- `POST /ami/reindex-and-search`

Use the generic Prism installer for normal Fabric and NeoForge pack testing. The
AMI EMI example below is a legacy compatibility-run target only; do not use it
for ordinary pack or worldgen validation.

Install into the legacy AMI EMI run:

```powershell
.\scripts\install-into-ami-run.ps1 `
  -AmiRepo "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex" `
  -RunName neoforge-emi
```

This installer builds the NeoForge subproject, removes any other AutoMine jars
from the selected AMI run, copies the current jar, and verifies its SHA-256 hash.

Trigger an inventory screenshot after the world is loaded:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex\run\neoforge-emi" `
  -Action open_inventory_and_screenshot `
  -Screenshot inventory.png
```

Open a singleplayer world without screen navigation:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex\run\neoforge-emi" `
  -Action open_world `
  -World "New World" `
  -TimeoutSeconds 90
```

Query AMI quest mirror state without taking a screenshot:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex\run\neoforge-emi" `
  -Action ami_quest_status `
  -Item "minecraft:redstone"
```

Open Zen Atelier's custom Alchemist Codex screen without taking a screenshot:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\Atelier\run\client" `
  -Action open_atelier_codex
```

Open any supported screen, then click inside it and capture the result:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\Atelier\run\client" `
  -Action open_screen_and_screenshot `
  -Screen SynthesisStationScreen `
  -Screenshot synthesis.png

.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\Atelier\run\client" `
  -Action click_screen_and_screenshot `
  -X 332 -Y 233 `
  -Button 0 `
  -Screenshot synthesis.filter.png
```

Open a container screen that needs a reflected menu constructor:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex\run\neoforge-create-compat" `
  -Action open_screen `
  -Screen <fqcn screen class> `
  -MenuClass <fqcn menu class> `
  -ScreenTitle <translation key>
```

Capture and return a cropped screenshot instead of the full image:

```powershell
.\scripts\send-automine-command.ps1 `
  -GameDir "C:\WorkDir\Minecraft Mods\AutomatedMaterialsIndex\AutomatedMaterialsIndex\run\neoforge-emi" `
  -Action open_inventory_and_screenshot `
  -Screenshot inventory.png `
  -CropRegion "420,80,760,620" `
  -CropOutput inventory.crop.png
```

`-CropRegion` is `x,y,width,height` in screenshot pixels. The full screenshot is preserved, and the script returns the cropped image path for visual inspection.

For a pack instance where AutoMine is only a temporary test dependency, close
the world with the blocking `world_close` HTTP action, then call `client_quit`
and wait on that running execution rather than polling status from model turns.
Remove the exact deployed AutoMine jar before synchronizing a pack manifest so
this dev-only helper is not captured as distributable content.

## Public builds and license

Original code is Copyright Ashley Wijnen-Riems and licensed under [MIT](LICENSE). Third-party notices and asset licenses remain in force. Provided **AS IS**, without warranty; support and updates are best effort.

Download runtime JARs from [GitHub Releases](https://github.com/ashleywr/automine-testing/releases). Match Minecraft 1.21.1, the loader, and the dependencies named in the release. Close Minecraft before installing in `mods/`; remove duplicate older versions. Back up worlds before changes.

## Build

Use Java 21 and the included Gradle wrapper.

```powershell
.\gradlew.bat :fabric:build :neoforge:build
```

Include the exact JAR filename, Minecraft/loader versions and a redacted `logs/latest.log` in bug reports.
