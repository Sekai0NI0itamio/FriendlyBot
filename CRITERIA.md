# Criteria: FriendlyBot (Forge 1.20.1 AI companion)

- [x] `gradle build` green on GitHub Actions (Java 17); nothing compiled locally
- [x] CI uploads `friendlybot-1.0.0.jar`; unit tests pass (protocol parse, name rules, snapshot formatting)
- [x] `/friendlybot create <name>` spawns exactly one fake-player companion per owner; skin copied from owner profile; `/friendlybot dismiss` removes it
- [x] Op-only `/friendlybot token <key>` (+ endpoint/model overrides); token never logged
- [x] `@<name> <message>` runs the agent loop against Nous longcat-free (non-streaming); tool results feed back up to the step limit
- [x] Every agent step sees fresh context: bot pos, inventory summary, 5x5x5 non-air blocks as relative coords, nearby entities with relative pos, nearby containers with relative pos
- [x] Model text goes to console only; only the `say` tool speaks in chat
- [x] Tools work: move_to/follow/stay, dig, place, select, inventory ops + sort, recipe lookup from RecipeManager, craft with container items, chest take/put, furnace run, eat, status
- [x] Scripting layer: tools are JSON pipelines over a Java primitive registry; a new tool using existing primitives ships via tools.json with no jar update
- [x] Unknown primitive or bad step fails that tool with a clear error, never the server thread
- [ ] `/friendlybot reload` refetches tools.json + prompt.md from this repo (behavior hot-swaps; Java stays in jar)
- [ ] Server-only safe: no custom packets, no client registries; bot self-registers through SimpleAuth if present

## v1.0.1 additions

- [x] get-token.py reuses hermes CLI auth or validates a pasted key, writes 0600 file, never prints secrets
- [ ] Bot walks via movement input with limb swing; teleports only when stuck
- [ ] interact tool right-clicks blocks with held item
- [ ] Token file fallback reads world/serverconfig/friendlybot-token.txt
