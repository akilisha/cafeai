# MILESTONE-13 — Gap Remediation

> Tracks execution of ROADMAP-13. Each phase has a status and acceptance
> criteria checkboxes. Update this file as work completes.

**Current Status:** ✅ Complete

| Phase | Description | Module | Status | Target |
|-------|-------------|--------|--------|--------|
| Phase 1 | Verify `RedisMemoryStrategy` | `cafeai-memory` | 🔴 Not Started | — |
| Phase 2 | Verify `MappedMemoryStrategy` (FFM) | `cafeai-memory` | 🔴 Not Started | — |
| Phase 3 | C3 `acme-claims`: Redis memory | `acme-claims` | ✅ Complete | Apr 2026 |
| Phase 4 | C3 `acme-claims`: Chroma vector store | `acme-claims` | ✅ Complete | Apr 2026 |
| Phase 5 | `RedisMemoryExample` | `cafeai-examples` | ✅ Complete | Apr 2026 |
| Phase 6 | `FfmMemoryExample` | `cafeai-examples` | ✅ Complete | Apr 2026 |
| Phase 7 | `ChromaVectorExample` | `cafeai-examples` | ✅ Complete | Apr 2026 |
| Phase 8 | Full build verification | all | ✅ Complete | Apr 2026 |

---

## Phase 1 — Verify `RedisMemoryStrategy`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `store()` serialises `ConversationContext` to JSON and writes to Redis with TTL
- [ ] `retrieve()` deserialises from Redis correctly
- [ ] `evict()` deletes the key
- [ ] `exists()` checks key presence without deserialising
- [ ] Lettuce client created once at construction, shared across calls
- [ ] `MemoryStrategy.redis("host", port)` factory overload works
- [ ] Session survives JVM restart (Redis persistence confirmed)
- [ ] Two JVM instances can share the same session via Redis
- [ ] `./gradlew :cafeai-memory:test` — all tests pass

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 2 — Verify `MappedMemoryStrategy` (FFM)

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] Session data written to a file on disk (confirmed via filesystem check)
- [ ] Session survives JVM restart (file persists across processes)
- [ ] Concurrent access is safe (`Arena.ofShared()`)
- [ ] `MemoryStrategy.mapped()` uses a default temp directory
- [ ] `MemoryStrategy.mapped(Path)` uses a specified directory
- [ ] Preview API warnings documented — note Java 22 graduation
- [ ] `./gradlew :cafeai-memory:test` — mapped strategy tests pass

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 3 — C3 `acme-claims`: Redis memory

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `acme-claims/build.gradle` has `cafeai-memory` dependency
- [ ] `app.memory(MemoryStrategy.redis("localhost", 6379))` wired in `ClaimsAgent.java`
- [ ] `docker-compose.yml` has Redis service (`redis:7-alpine`, port 6379)
- [ ] `README.md` updated with Redis prerequisite and `docker-compose up -d`
- [ ] Session follow-up works (claim context remembered across requests)
- [ ] Session survives application restart with Redis running
- [ ] Meaningful error if Redis unreachable at startup (not NPE)

### Before / After
```java
// Before (all capstones)
app.memory(MemoryStrategy.inMemory());

// After (acme-claims Phase 3)
app.memory(MemoryStrategy.redis("localhost", 6379));
```

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 4 — C3 `acme-claims`: Chroma vector store

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `acme-claims/build.gradle` has Chroma dependency resolved
- [ ] `app.vectordb(VectorStore.chroma("http://localhost:8000"))` wired in `ClaimsAgent.java`
- [ ] `docker-compose.yml` has Chroma service (`chromadb/chroma:latest`, port 8000)
- [ ] `AcmeKnowledgeBase.seed(app)` populates Chroma on startup
- [ ] `response.ragDocuments().size()` returns 3 on real claims queries
- [ ] Chroma UI at `http://localhost:8000` shows the ingested collection
- [ ] Claims responses cite policy documents correctly

### Before / After
```java
// Before (all capstones)
app.vectordb(VectorStore.inMemory());
app.embed(EmbeddingModel.local());
app.rag(Retriever.semantic(3));

// After (acme-claims Phase 4)
app.vectordb(VectorStore.chroma("http://localhost:8000"));
app.embed(EmbeddingModel.local());
app.rag(Retriever.semantic(3));
```

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 5 — `RedisMemoryExample`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `RedisMemoryExample.java` exists in `cafeai-examples`
- [ ] `cafeai-examples/redis/docker-compose.yml` exists
- [ ] `cafeai-examples/redis/README.md` explains the demo step-by-step
- [ ] README walkthrough proves session persists across JVM restarts
- [ ] Inline comments explain when to choose Redis over in-memory or FFM
- [ ] `./gradlew :cafeai-examples:compileJava` passes

### Teaching goal
A developer reading this example should understand:
- Why in-memory fails for multi-instance deployments
- Why Redis is the right escape valve (not the default)
- That the code change is one line: `inMemory()` → `redis(...)`

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 6 — `FfmMemoryExample`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `FfmMemoryExample.java` exists in `cafeai-examples`
- [ ] `cafeai-examples/ffm/README.md` explains the demo step-by-step
- [ ] README walkthrough proves session persists across JVM restarts
- [ ] A filesystem file is visible after the first request (confirming off-heap)
- [ ] Inline comments explain `MemorySegment`, `Arena`, and why this is
  architecturally coherent with CafeAI's FFM usage for ONNX bindings
- [ ] No Docker required — zero external dependencies
- [ ] `./gradlew :cafeai-examples:compileJava` passes

### Teaching goal
A developer reading this example should understand:
- What "off-heap" means and why it matters for session memory
- Why crash recovery is free when sessions are memory-mapped
- That the code change is one line: `inMemory()` → `mapped()`
- The architectural coherence of using FFM for both ML bindings and memory

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 7 — `ChromaVectorExample`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ChromaVectorExample.java` exists in `cafeai-examples`
- [ ] `cafeai-examples/chroma/docker-compose.yml` exists
- [ ] `cafeai-examples/chroma/README.md` explains the demo step-by-step
- [ ] RAG retrieval works against Chroma
- [ ] README walkthrough demonstrates documents persisting across restarts
- [ ] Chroma REST API shows the collection
- [ ] `./gradlew :cafeai-examples:compileJava` passes

### Teaching goal
A developer reading this example should understand:
- Why `inMemory()` loses documents on restart
- That `VectorStore.chroma(url)` is a one-line swap
- That the knowledge base can be managed externally, separate from the app
- When to use Chroma (local/dev) vs PgVector (enterprise production)

### Notes
<!-- Add implementation notes, blockers, decisions here -->

---

## Phase 8 — Full build verification

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `./gradlew clean build` exits with BUILD SUCCESSFUL
- [ ] Test count >= 311
- [ ] Zero new compile errors in any module
- [ ] `cafeai-examples` compiles with all new example files
- [ ] `acme-claims` starts correctly with `docker-compose up -d`

### Notes
<!-- Add build output here when complete -->

---

## Completion Definition

MILESTONE-13 is **complete** when:
1. All 8 phases show ✅ Complete status
2. Phase 8 build verification passes with >= 311 tests
3. `acme-claims` demonstrates Redis memory + Chroma vector store running together
4. Three new examples in `cafeai-examples` each prove their memory/vector strategy works

**What success looks like in concrete terms:**

```
./gradlew :cafeai-memory:test
  RedisMemoryStrategyTest  PASSED  (session survives restart)
  MappedMemoryStrategyTest PASSED  (session survives restart, file visible)

cd acme-claims && docker-compose up -d && ./gradlew run
  INFO  RedisMemoryStrategy   -- Connected to Redis at localhost:6379
  INFO  ChromaVectorStore     -- Connected to Chroma at http://localhost:8000
  INFO  CafeAIRagPipeline     -- Ingested 8 documents into Chroma collection 'acme-claims'
  INFO  HelidonWebServer      -- Started acme-claims on port 8080

./gradlew :cafeai-examples:compileJava
  BUILD SUCCESSFUL
  # RedisMemoryExample.java, FfmMemoryExample.java, ChromaVectorExample.java all compile
```
