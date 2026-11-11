# Collision Map

A RuneLite plugin that outlines the area you can walk to in Old School RuneScape, and marks the doors, gates and shortcuts that lead out of it.

## Features

* **Reachable-area walls:** Draws the walls around everything you can actually walk to from where you are standing, instead of every blocked tile in the scene. A cave in the middle of a sea of unwalkable tiles shows just the cave.
* **Doors and shortcuts:** Closed doors, gates, rocks, crossings and other passages that lead out of your area are marked in a different color, so you can see what is connected to what.
* **Look through doors:** Optionally show the area behind each door (and behind those doors, up to three levels deep).
* **Object markers:** Optionally outline the click box of interactable objects and label them, for example `Gate (Open)`.
* **Fast:** Reachability is recalculated only when you move or the scene changes, and straight runs of walls are merged into single lines. Drawing is optimized for both the GPU plugin and the standard renderer.

## Configuration

Open the RuneLite configuration panel, search for **Collision Map**, and adjust the settings.

| Section | Setting | Default | Description |
|---|---|---|---|
| Walls | Draw radius | 20 | How many tiles around you to draw (3-52). |
| Walls | Wall color | Orange | Color and transparency of walls. |
| Walls | Wall thickness | 2 | Thickness of wall lines (1-8). |
| Doors & shortcuts | Look through doors | 1 | How many doors deep to show. `0` shows only your own area (doors are still marked). |
| Doors & shortcuts | Door color | Green | Color of doors, gates and shortcuts that lead out of your area. |
| Objects | Mark objects | Off | Outline the click box of interactable objects. |
| Objects | Labels | On | Write the object's name and action above it. |
| Objects | Object color | Yellow | Color of click boxes and labels. |
| Objects | Actions to mark | Open, Close, Climb, Enter, ... | Comma separated list. An object is marked if any of its actions starts with one of these. |

## How it works

On each game tick the plugin flood-fills the scene's collision data outward from your tile, respecting walls and one-way edges. Whenever the fill is stopped by a door, gate or shortcut, that passage is recorded and, depending on **Look through doors**, the fill continues on the other side.

Passages come from two places:

* A bundled table of known doors, gates and shortcuts, with exact start and end tiles. Entries that need a higher skill level than yours are ignored.
* Live objects in the scene that have an `Open` action, which catches doors the table does not know about.

## Limitations

* The overlay is drawn on top of the game scene, so walls are not hidden by buildings, trees or other models. RuneLite's overlay system has no depth buffer for plugins to use.
* Quest, item and varbit requirements for shortcuts are not checked, only skill levels. A door that is locked for you may still be shown as a passage.
* Shortcut data is not used inside instanced areas, though doors detected from objects still work.
* Shortcuts that are not in the table appear only as labeled objects and do not extend the reachable area.

## Screenshots

## Installation

Available through the RuneLite Plugin Hub once approved.

## Credits

The bundled door and shortcut table is derived from the transport data of the [Shortest Path](https://github.com/Skretzo/shortest-path) plugin by Skretzo, which is distributed under the BSD 2-Clause License. See `transports.NOTICE.txt` for the license text.

## License

BSD 2-Clause License. See [LICENSE](https://github.com/schaboomafoo/collision-map/blob/master/LICENSE) for details.
