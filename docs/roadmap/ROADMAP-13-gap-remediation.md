# ROADMAP-13 — Gap Remediation

> Addresses the structural gaps identified after completing Capstones 1–4.
> These are not polish items. They are unproven claims and architectural
> failures that undermine CafeAI's core differentiators.

---

## Context

Four capstones were built to validate the framework. The gap analysis that
followed identified two failures that matter above all others:

**Gap A — The tiered memory architecture was never demonstrated.**
`MemoryStrategy.redis()` and `MemoryStrategy.mapped()` (FFM) exist in the
API vocabulary and are listed as core differentiators in the SPEC. Every
capstone used `MemoryStrategy.inMemory()`. The claim that CafeAI offers
production-grade off-heap and distributed session memory is unproven.

**Gap B — Vector store versatility was never demonstrated.**
`VectorStore.chroma()` and `VectorStore.pgVector()` are in the `cafeai-rag`
dependency list and in the SPEC's technology stack. Every capstone used
`VectorStore.inMemory()`. The claim that CafeAI supports pluggable vector
backends is unproven.

**Gap C — Capstone 4 (`atlas-inbox`) treats CafeAI as noise.**
The most complex capstone — multimodal PDF classification, invoice extraction,
tool-calling — bypasses `app.prompt()` entirely for its heavy work, using
raw LangChain4j directly. Root cause: `app.prompt()` has no multimodal
support. The capstone worked around the gap instead of exposing it.

This roadmap addresses Gap A and Gap B directly. Gap C (multimodal pipeline
support) is a larger architectural addition tracked separately.

---

## Dependency Map

```
Phase 1  (verify RedisMemoryStrategy)
    └── Phase 2  (verify MappedMemoryStrategy / FFM)
    └── Phase 3  (C3 acme-claims: Redis memory)       <- depends on Phase 1
    └── Phase 4  (C3 acme-claims: Chroma vector store)
    └── Phase 5  (cafeai-examples: RedisMemoryExample)   <- depends on Phase 1
    └── Phase 6  (cafeai-examples: FfmMemoryExample)     <- depends on Phase 2
    └── Phase 7  (cafeai-examples: ChromaVectorExample)  <- depends on Phase 4
    └── Phase 8  (311 tests still passing)
```

---

## Phase 1 — Verify and complete `RedisMemoryStrategy`

**Goal:** Confirm `RedisMemoryStrategy` is a real implementation, not a stub.
Fix any gaps.

**Module:** `cafeai-memory`

**What we know from transcripts:**
- `RedisMemoryStrategy.java` exists with `store()` and a try/catch
- `retrieve()` was not confirmed — may return null or be unimplemented
- `MemoryStrategy.redis()` factory method exists in `MemoryStrategy.java`
- Lettuce (reactive Redis client) is listed in the tech stack

**Tasks:**
- [ ] Read `RedisMemoryStrategy.java` in full
- [ ] Verify `store()` serialises `ConversationContext` to JSON and writes to Redis with TTL
- [ ] Verify `retrieve()` deserialises from Redis correctly
- [ ] Verify `evict()` deletes the key
- [ ] Verify `exists()` checks key presence without deserialising
- [ ] Verify connection lifecycle — Lettuce client created once, shared, closed on app shutdown
- [ ] Verify `MemoryStrategy.redis("localhost", 6379)` factory overload exists
- [ ] Run `./gradlew :cafeai-memory:test` — all memory tests pass
- [ ] Manual smoke test: store a `ConversationContext`, restart JVM, retrieve it — confirms Redis persistence

**Acceptance criteria:**
- [ ] `RedisMemoryStrategy` fully implements `MemoryStrategy` interface
- [ ] Session survives JVM restart (proving it is not in-memory)
- [ ] Two JVM instances sharing the same Redis can access the same session
- [ ] All `cafeai-memory` tests pass

---

## Phase 2 — Verify and document `MappedMemoryStrategy` (FFM)

**Goal:** Confirm `MappedMemoryStrategy` works correctly and the FFM
off-heap story is demonstrable, not theoretical.

**Module:** `cafeai-memory`

**What we know from transcripts:**
- `MappedMemoryStrategy.java` exists and compiled (with Java 21 preview warnings)
- Uses `Arena.ofShared()`, `FileChannel.map()`, `MemorySegment`
- Preview warnings will go away on Java 22+ — not a blocker for Java 21

**Tasks:**
- [ ] Read `MappedMemoryStrategy.java` in full
- [ ] Verify session data is written to a file (not heap)
- [ ] Verify session data survives JVM restart (file persists across processes)
- [ ] Verify concurrent access is safe (`Arena.ofShared()` implies it should be)
- [ ] Verify `MemoryStrategy.mapped()` and `MemoryStrategy.mapped(Path)` factory overloads
- [ ] Run `./gradlew :cafeai-memory:test` — mapped strategy tests pass
- [ ] Manual smoke test: store a session, kill JVM, restart, retrieve — confirms crash recovery

**Acceptance criteria:**
- [ ] `MappedMemoryStrategy` fully implements `MemoryStrategy` interface
- [ ] Session data is stored in a file on disk (confirm with filesystem check)
- [ ] Session survives JVM restart
- [ ] All preview API warnings are documented with a note about Java 22 graduation

---

## Phase 3 — C3 `acme-claims`: swap to `MemoryStrategy.redis()`

**Goal:** Replace `inMemory()` with `redis()` in Capstone 3. The claims
domain is the right fit — sessions should survive restarts and be sharable
across AP staff instances.

**Module:** standalone `acme-claims` capstone project

**Tasks:**
- [ ] Add `cafeai-memory` dependency to `acme-claims/build.gradle`
- [ ] Add Lettuce dependency (if not transitive via `cafeai-memory`)
- [ ] Replace `app.memory(MemoryStrategy.inMemory())` with
      `app.memory(MemoryStrategy.redis("localhost", 6379))`
- [ ] Add `docker-compose.yml` with Redis service:
      ```yaml
      services:
        redis:
          image: redis:7-alpine
          ports: ["6379:6379"]
      ```
- [ ] Update `acme-claims/README.md` with Redis prerequisite and
      `docker-compose up -d` instruction
- [ ] Verify session memory works end-to-end with Redis running
- [ ] Verify graceful error if Redis is unavailable (meaningful error, not NPE)

**Acceptance criteria:**
- [ ] `./gradlew run` starts cleanly with Redis running
- [ ] Session follow-up works (claim context remembered across requests)
- [ ] Session survives application restart with Redis running
- [ ] Clear error message if Redis is not reachable at startup

---

## Phase 4 — C3 `acme-claims`: swap to `VectorStore.chroma()`

**Goal:** Replace `VectorStore.inMemory()` with `VectorStore.chroma()` in
Capstone 3. The insurance knowledge base is a realistic candidate for an
external vector store — it would be managed separately from the application
and updated as policy documents change.

**Module:** standalone `acme-claims` capstone project

**Tasks:**
- [ ] Add `langchain4j-chroma` dependency to `acme-claims/build.gradle`
      (already in `cafeai-rag/build.gradle` — check if it's exported)
- [ ] Replace `app.vectordb(VectorStore.inMemory())` with
      `app.vectordb(VectorStore.chroma("http://localhost:8000"))`
- [ ] Update `docker-compose.yml` to add Chroma service:
      ```yaml
        chroma:
          image: chromadb/chroma:latest
          ports: ["8000:8000"]
      ```
- [ ] Confirm `AcmeKnowledgeBase.seed(app)` still works — ingestion pipeline
      writes to Chroma on startup
- [ ] Confirm RAG retrieval returns documents from Chroma correctly
- [ ] Verify `response.ragDocuments().size()` is non-zero on a real claims query

**Acceptance criteria:**
- [ ] `./gradlew run` starts cleanly with Chroma running
- [ ] `response.ragDocuments().size()` returns 3 on claims queries
- [ ] Chroma UI at `http://localhost:8000` shows the ingested collection
- [ ] Claims responses cite policy documents correctly (RAG grounding test)

---

## Phase 5 — `cafeai-examples`: `RedisMemoryExample.java`

**Goal:** A standalone, runnable example in `cafeai-examples` that explicitly
demonstrates Redis-backed session memory — what it is, why you'd use it,
and how to swap it in with one line.

**Module:** `cafeai-examples`

**Structure:**
```
cafeai-examples/src/main/java/io/cafeai/examples/
    RedisMemoryExample.java    <-- new
```

**What the example demonstrates:**
1. `app.memory(MemoryStrategy.inMemory())` — establish baseline, explain limitation
2. Same app, `app.memory(MemoryStrategy.redis(...))` — one-line swap
3. Session survives application restart — the teaching moment
4. Two app instances sharing sessions — the distributed use case

**Tasks:**
- [ ] Write `RedisMemoryExample.java` as a self-contained HTTP server
- [ ] Include inline comments explaining when to use Redis vs in-memory vs FFM
- [ ] Include `docker-compose.yml` in `cafeai-examples/redis/`
- [ ] Include `README.md` in `cafeai-examples/redis/` with:
      - What this demonstrates
      - Prerequisites (Redis via Docker)
      - Step-by-step walkthrough proving session persistence
- [ ] `./gradlew :cafeai-examples:compileJava` passes

**Acceptance criteria:**
- [ ] Example compiles and runs
- [ ] README walkthrough proves session persists across restarts
- [ ] Inline comments explain the tiered memory model clearly

---

## Phase 6 — `cafeai-examples`: `FfmMemoryExample.java`

**Goal:** A standalone example demonstrating FFM-backed off-heap session
memory. This is CafeAI's most distinctive technical claim — conversation
history stored in Java `MemorySegment`, SSD-backed, no network, no cloud.

**Module:** `cafeai-examples`

**Structure:**
```
cafeai-examples/src/main/java/io/cafeai/examples/
    FfmMemoryExample.java    <-- new
```

**What the example demonstrates:**
1. `app.memory(MemoryStrategy.mapped())` — zero dependency, SSD-backed
2. Session data written to a file (show the file on disk)
3. Session survives JVM restart — crash recovery for free
4. No Redis, no network, no cloud cost — the differentiator

**Tasks:**
- [ ] Write `FfmMemoryExample.java` as a self-contained HTTP server
- [ ] Include inline comments explaining FFM, `MemorySegment`, `Arena`
- [ ] Include explanation of why this is architecturally coherent
      (same FFM API used for ONNX bindings and for session memory)
- [ ] Include `README.md` in `cafeai-examples/ffm/` with:
      - What this demonstrates
      - No prerequisites (zero dependencies beyond the JDK)
      - Step-by-step walkthrough proving session persistence across restarts
- [ ] `./gradlew :cafeai-examples:compileJava` passes

**Acceptance criteria:**
- [ ] Example compiles and runs
- [ ] README walkthrough proves session persists across JVM restarts
- [ ] Inline comments explain the FFM memory model clearly
- [ ] A filesystem file is visible after the first request

---

## Phase 7 — `cafeai-examples`: `ChromaVectorExample.java`

**Goal:** A standalone example demonstrating Chroma as an external vector
store, contrasted with `inMemory()`. Shows how to swap vector backends with
one line and why you'd want to.

**Module:** `cafeai-examples`

**Structure:**
```
cafeai-examples/src/main/java/io/cafeai/examples/
    ChromaVectorExample.java    <-- new
```

**What the example demonstrates:**
1. `app.vectordb(VectorStore.inMemory())` — baseline: documents lost on restart
2. Same app, `app.vectordb(VectorStore.chroma(...))` — one-line swap
3. Documents persist across restarts in Chroma
4. Chroma collections visible in the API — documents are externally managed

**Tasks:**
- [ ] Write `ChromaVectorExample.java` with a small knowledge base (3–5 documents)
- [ ] Include `docker-compose.yml` in `cafeai-examples/chroma/`
- [ ] Include `README.md` proving the difference between in-memory and Chroma
- [ ] `./gradlew :cafeai-examples:compileJava` passes

**Acceptance criteria:**
- [ ] Example compiles and runs
- [ ] RAG retrieval works against Chroma
- [ ] README walkthrough demonstrates documents persisting across restarts
- [ ] Chroma REST API at `localhost:8000/api/v1/collections` shows the collection

---

## Phase 8 — Full build verification

**Goal:** Confirm the gap remediation work leaves the main build in the same
state it started in.

**Tasks:**
- [ ] `./gradlew clean build` — 311 tests pass (or more, if new tests were added)
- [ ] No new compile errors in any module
- [ ] `cafeai-examples` module compiles with the new example files
- [ ] `acme-claims` compiles and starts correctly with Docker services running

**Acceptance criteria:**
- [ ] Test count >= 311
- [ ] BUILD SUCCESSFUL across all modules
- [ ] Zero regressions

---

## What this roadmap does NOT cover

- **Gap C (multimodal `app.prompt()`)** — adding `ImageContent` / `PdfFileContent`
  support to the CafeAI prompt pipeline. This is significant architectural work
  and is tracked separately. The `atlas-inbox` capstone will be revisited once
  this is implemented.

- **Structured output `app.prompt().returning(Class)`** — deferred per gap
  analysis decision.

- **Documentation gaps** (chains removed, topic boundary limitation, prompt
  vs LangChain4j boundary) — small writes, tracked in the gap analysis doc,
  can be done in parallel with this roadmap.
