# MILESTONE-08: Dependency Injection — CDI, Service Loaders, and `cafeai-cdi`

**Roadmap:** ROADMAP-08  
**Modules:** `cafeai-core` (SPI), `cafeai-cdi` (new optional module)  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Module | Status | Completed |
|---|---|---|---|---|
| Phase 1 | `CafeAIConfigurer` + Service Loader bootstrap | `cafeai-core` | 🔴 Not Started | — |
| Phase 2 | `CafeAIModule` SPI + module self-registration | `cafeai-core` + all modules | 🔴 Not Started | — |
| Phase 3 | SPI package structure + JPMS `module-info.java` | `cafeai-core` + all modules | 🔴 Not Started | — |
| Phase 4 | `cafeai-cdi` — CDI integration module | `cafeai-cdi` | 🔴 Not Started | — |
| Phase 5 | `@CafeAIRoute` declarative routing | `cafeai-cdi` | 🔴 Not Started | — |
| Phase 6 | DI guide + extension authoring guide | `docs/` | 🔴 Not Started | — |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

_Nothing completed yet._

---

## In-Progress Items

_Nothing in progress yet._

---

## Decisions & Design Updates

_No decisions recorded yet._

> **How to use this section:**
> This roadmap sits at the intersection of multiple Java infrastructure concerns —
> JPMS, CDI, Service Loader, Helidon lifecycle. When any of these surfaces an
> unexpected constraint during implementation, record it here immediately.
> This is the most likely roadmap to surface JPMS-related friction
> (Service Loader + JPMS `provides`/`uses` declarations have known sharp edges).

---

## Blockers & Issues

_No blockers recorded yet._

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1 complete | — | — | Unblocks: all Gen AI modules can now self-register |
| Phase 2 complete | — | — | Unblocks: modules auto-discoverable on classpath |
| Phase 3 complete | — | — | JPMS hardening |
| Phase 4 complete | — | — | CDI integration available |
| Phase 5 complete | — | — | Declarative routing available |
| Phase 6 complete | — | — | |
| MILESTONE-08 closed | — | — | |

---

## Notes & Observations

> **Phase 1 is deceptively simple but architecturally foundational.**
> `CafeAIConfigurer` is a ten-line interface. But its placement in
> `io.cafeai.core.spi`, its Service Loader discovery, its ordering contract,
> and its `IllegalStateException` guard after `listen()` are all load-bearing.
> Get Phase 1 right before touching Phase 4. A flawed seam will propagate
> into every CDI integration built on top of it.

> **Phase 3 (JPMS) will surface hidden dependencies.**
> Adding `module-info.java` to `cafeai-core` forces explicit declaration of
> every dependency. This is healthy but will likely reveal transitive dependencies
> that are currently implicit. Budget time to resolve each one. Record all
> JPMS-driven dependency decisions here.

> **Phase 4 CDI lifecycle ordering is critical.**
> The CDI extension must fire AFTER all `@Inject` fields are populated in
> `CafeAIConfigurer` beans. Firing before injection completes means
> `configure(app)` runs with null dependencies. Verify with a test that
> deliberately uses an injected dependency in `configure()` and confirms
> it is non-null.

> **The three-tier model is a permanent architectural commitment.**
> Any future contributor who is tempted to add `@Inject` support to
> `app.get()` handlers, or to make route handlers managed CDI beans by
> default, is violating this ADR. The separation of tiers is intentional
> and must be defended. Record any such pressure here so the rationale
> is visible to the team.

> **Zero-DI must be verified at every phase.**
> After each phase is completed, run the `HelloCafeAI.java` example
> (which uses zero DI) and confirm it still works without modification.
> This is the regression test for the zero-DI commitment.
