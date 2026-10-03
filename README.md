# FriendlyBot

An AI companion for **Forge 1.20.1** offline-mode servers, powered by a free
LongCat model. Server-side only: companions are real fake players, so vanilla
clients see them with no client mod.

## Player commands

- `/friendlybot create <name>` — spawn your companion (one each, 1-16 letters/digits/underscores)
- `/friendlybot dismiss` — send it home
- `@<name> <message>` in chat — ask it things, e.g. `@bob help me craft a piston from my chests`
- Ops: `/friendlybot token <key>` (AI key, never logged), `/friendlybot model <id>`,
  `/friendlybot endpoint <url>`, `/friendlybot reload` (refetch tools + prompt from this repo)

## How it works

Each message runs an agent loop: fresh world context (position, inventory,
5x5x5 terrain as relative coords, entities, chests) → model answers JSON tool
calls → the mod executes them → results feed back. Model chatter goes to the
console only; the `say` tool is the one path to chat.

## Scripting layer

The mod is a runtime: Java provides ~30 primitives (move, dig, place,
inventory, recipes, crafting, chests, furnace, eat, say...), and `config/tools.json`
composes them into tools. **New tools from existing primitives ship through
`tools.json` with no jar update** — `/friendlybot reload` picks them up live.
New *information* (a new primitive) still needs a jar release.

## Building

CI-only: every push runs `gradle build` (Java 17, unit tests) on GitHub
Actions and uploads the jar. Tags `v*` publish a release.
