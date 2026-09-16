<!-- OUTLINE DRAFT — structure only, for review. Not for publishing as-is.
     Replaces the old "Coming in ROADMAP-17" stub, whose design (@CafeAITool,
     INTERNAL/EXTERNAL trust levels) never shipped — see [[agents-mcp-direction]]. -->

# Tool Use in Java — Giving the LLM Actions to Take

*Post 7 of 12 in the CafeAI series*

---

## Opening — what was supposed to ship vs. what actually shipped

- Narrative hook, in the series' honest-about-gaps voice (same move post 09 makes
  about `invoice-processor`'s multimodal bypass): this post was originally going to
  describe a bespoke CafeAI tool system — `@CafeAITool` annotations, a
  framework-owned dispatch loop, INTERNAL/EXTERNAL trust levels for Java tools
  vs. MCP.
- None of that got built. Building a second tool-calling engine next to
  LangChain4j's own was solving a problem that didn't exist — LangChain4j
  `AiServices` already owns tool dispatch, schema generation, and the
  calling loop correctly.
- What shipped instead: `cafeai-agents` is a **thin HTTP binding** over
  `AiServices` — a name, session threading, guardrail pre-screening, an
  observability context. Tool use is just LangChain4j's `@Tool`, unmodified.
- Frame this as the same lesson as the middleware pattern (post 2): CafeAI's
  job is composition, not reinvention. Drop trust levels entirely — not a
  redesign, a design that was never real.

## The shape of it: `app.agent(name, Interface).tool(instance)`

- Quote the real registration + resolution API directly from `CafeAI.java`
  (javadoc already has a clean canonical example — `SupportAgent` interface,
  `.tool(new OrderLookupTool())`, `.memory()`, `.guard()`).
- Two-step split worth calling out explicitly: `agent(name, Class)` registers
  at startup (before `listen()`); `agent(name, Class, sessionId)` resolves the
  live proxy inside a route handler. Session threading is what CafeAI adds
  that a bare `AiServices.builder()` doesn't give you for free.

## A minimal tool, end to end

- Walk `support-desk`'s `GitHubTools.getIssueStatus(String)` top to bottom —
  smallest real `@Tool` in the codebase, one method, one `@Tool` description
  string, plain `String` in/out. Mirrors post 1's "smallest possible
  application" beat.
- Show the model deciding on its own whether to call it — no forced
  invocation, no manual dispatch code in the capstone at all.

## Composing several tools on one agent

- `meridian-qualify`'s `QualificationTools` or `acme-claims`'s
  `ClaimsApiTools` — an agent with multiple `@Tool` methods, model chooses
  which to call and in what order for a single request.
- Point back to guardrails (post 8) here: tool results flow back through the
  same pipeline, so a `POST_LLM` guardrail still sees what the model does
  with what a tool returned.

## One honest practical finding

- TBD during drafting — pull a real wrinkle from a capstone's tool
  implementation or test (candidates: `meridian-qualify`'s "forced
  tool-protocol" framing in `capstones/README.md`, or a `BillingToolsTest`
  case in `invoice-processor`). Matches the series' pattern of one concrete,
  specific lesson per post (post 9's multi-page-PDF fix is the model).

## Forward link + closing

- "Post 8 covers guardrails — including what happens when a tool call itself
  needs to be screened."
- Standard closing tagline.
