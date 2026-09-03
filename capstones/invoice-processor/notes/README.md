# Historical notes

Dated design and validation records from `atlas-inbox` (this capstone's former
name), written against CafeAI 0.1.0–0.1.1. Kept for context, not maintained.

The current app differs from what these describe:

- `@CafeAITool` / `app.tool()` → LangChain4j `@Tool` on a `ReconciliationAgent`
  registered with `app.agent("reconciler", ReconciliationAgent.class)`
- `MultimodalChatService` was already deleted in favour of `app.vision()`
- consumed as `project(':cafeai-*')` in the umbrella build, not a published artifact
- Java 23, no `--enable-preview`

See the top-level `README.md` for how to build and run.
