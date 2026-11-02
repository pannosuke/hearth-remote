# hearth-remote

A planned Android phone remote for the family's Sharp AQUOS Android TV. It runs on Eric's **Samsung Galaxy S24 Ultra**, replaces the "Remote ATV" app, and pairs with the custom TV launcher **Hearth** ([pannosuke/hearth-launcher](https://github.com/pannosuke/hearth-launcher)).

**Status: planning. No code yet.** The full requirements, the recommended architecture and the voice-command design are in [`handoff/PLAN.md`](handoff/PLAN.md).

Per Eric: **share the plan with him and get his OK before you build.**

## For a new maintainer
1. Read [`handoff/PLAN.md`](handoff/PLAN.md).
2. Read the launcher's handoff: [hearth-launcher/handoff/HANDOFF.md](https://github.com/pannosuke/hearth-launcher/blob/main/handoff/HANDOFF.md). It covers the household context, Eric's preferences, the TV quirks, Jellyfin details, adb setup and secrets handling. All of it applies here too.
3. Secrets (the Jellyfin API key, any pairing tokens) never go into this repo.
