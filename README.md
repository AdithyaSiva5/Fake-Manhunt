# Fake-Manhunt

A Paper plugin for recording "fake" Minecraft manhunts: save the world at any point, roll back when a take goes wrong, make the runner (or hunters) unkillable, and get ender pearls from piglins without grinding.

Everything is in one jar, and every feature can be switched on or off in `config.yml`.

## Features

| Feature | What it does |
|---|---|
| **/save and /load** | Autosave is off. Progress only sticks when you run `/save`. `/load` kicks everyone, restores the world to the last save and restarts the server. |
| **/qsave and /qload** | Instant player-only save. Puts everyone back where they were (position, health, hunger, saturation, inventory, armor, XP, effects, time and weather) without restarting or kicking anyone. Blocks are not rolled back. |
| **/immortal** | Two modes. `damage`: you take hits (knockback, hurt sound, red flash) but never drop below half a heart, so PvP looks normal. `nodamage`: hits are cancelled with no hurt effect, so a failed clutch doesn't give it away. |
| **Boosted bartering** | Piglins hand out ender pearls, fire resistance potions and obsidian far more often. Default pearl rate is 50% (vanilla is about 2%). |

## Requirements

- **Paper** server, version 26.1 or newer
- **Java 25**
- **Linux/macOS** host for `/load` (it uses the included `start.sh`)

## Installation

1. Download `FakeManhunt.jar` from the [latest release](https://github.com/AdithyaSiva5/Fake-Manhunt/releases/latest) and put it in your server's `plugins/` folder.
2. Copy [`start.sh`](start.sh) into your server folder, next to `paper.jar`, and make it executable:
   ```bash
   chmod +x start.sh
   ```
3. Start the server **with `start.sh`** instead of running `java -jar` yourself:
   ```bash
   ./start.sh
   ```
   `start.sh` is what restores the world and restarts the server after `/load`. Without it, `/load` just stops the server.
4. On first start, the plugin creates `plugins/FakeManhunt/config.yml`. Edit it to suit your video, then run `/manhunt reload`.

> **Heads up:** every time the server starts through `start.sh`, the world is restored from the last `/save`. Anything not saved is gone, even after a normal restart. That's the point. Run `/save` often.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/save` | Save the world as the new restore point. `/save-all` does the same thing. | `fakemanhunt.use` |
| `/load` | Roll the world back to the last `/save`. Kicks everyone; rejoin after about 30 seconds. | `fakemanhunt.use` |
| `/qsave` | Quick save of all online players, plus time and weather. Aliases: `/quicksave`, `/minisave`. | `fakemanhunt.use` |
| `/qload [player]` | Instantly put everyone (or just one player) back to the last `/qsave`. Aliases: `/quickload`, `/miniload`. | `fakemanhunt.use` |
| `/immortal [player]` | Toggle `damage` mode immortality for yourself or the named player. | `fakemanhunt.immortal` |
| `/immortal damage\|nodamage\|off [player]` | Set a specific mode, or turn it off. | `fakemanhunt.immortal` |
| `/manhunt reload` | Reload `config.yml` without restarting. | `fakemanhunt.reload` |

All permissions default to **OP only**.

## Configuration

`plugins/FakeManhunt/config.yml`:

```yaml
save-load:
  enabled: true
  save-all-is-save: true        # /save-all also updates the restore point
  load-kick-message: "§e[Manhunt] Loading last save...\n§7Rejoin in ~30 seconds."

quick-save:
  enabled: true

immortal:
  enabled: true
  min-health: 1.0               # 1.0 = half a heart, 20.0 = full health
  allow-kill-command: true      # /kill still works on immortal players

barter:
  enabled: true
  ender-pearl:
    chance: 50                  # % of barters that give pearls
    min: 4
    max: 8
  fire-resistance:
    chance: 20
    splash-chance: 30           # % of fire res drops that are splash potions
  obsidian:
    chance: 25
    min: 1
    max: 3
```

**Barter chances** are rolled in order (pearls, then fire resistance, then obsidian). Keep the three adding up to 100 or less. Whatever is left over gives the normal vanilla drop. For example, `50 + 20 + 25 = 95`, so 5% of barters are vanilla.

Vanilla rates for comparison: pearls about 2.2%, fire resistance about 3.5%, obsidian about 8.7%.

The quick save is stored in `plugins/FakeManhunt/quicksave.yml` and the immortal player list separately in `plugins/FakeManhunt/immortal-players.yml`. Both live outside the world folder, so they survive `/load`.

## How /save and /load work

- `/save` flushes the world to disk, then copies the world folders into `snapshot/` in the background, so the server doesn't freeze.
- `/load` writes a `restart.flag` file and stops the server. `start.sh` sees the flag, replaces the world folders with the copies in `snapshot/` and starts the server again.
- Typing `stop` in the console shuts down for real. No flag is written, so `start.sh` exits.
- The first time the plugin runs, it takes an automatic snapshot of the starting world.

## Building from source

You need a JDK 25 and a Paper server that has been started once, so its `libraries/` folder exists.

```bash
./build.sh /path/to/your/paper/server
```

This compiles `src/` and writes a fresh `FakeManhunt.jar`.
