# Context Memory Without the Cloud Tax

*Post 5 of 12 in the CafeAI series*

---

Every AI tutorial that reaches the "conversation history" section says the same thing: store sessions in Redis.

Redis is excellent infrastructure. Widely understood, operationally mature, with client libraries in every language. It is also, for most AI applications, unnecessary — a cloud tax paid to solve a problem that does not exist at the scale the application is actually running.

CafeAI's memory model starts from a different premise: most production AI applications run on a single server, serve thousands of concurrent users, and need sessions that survive restarts. All three of these requirements are satisfied by an SSD, not by Redis.

This post covers the full tiered memory model — why each rung exists, when to graduate to the next one, and why the default is not Redis.

---

## The Conversation History Problem

Language models are stateless. Each API call is independent — the model has no memory of what was said in prior turns unless you include it explicitly. Building a conversational AI application means managing that history yourself.

The naive approach: store history in a `List<Message>` in the handler. This works for a single request. It breaks the moment the user makes a second request — the handler is stateless, the JVM may have garbage-collected the list, and the second request has no context.

The correct approach: persist the conversation history between requests, keyed by session identifier. Every request loads the history, appends the new turn, calls the LLM with the full context, and writes the updated history back.

CafeAI handles all of this automatically when `.session(sessionId)` is called on a prompt request. The developer provides the session ID — usually from a request header — and the framework manages the rest.

---

## The Five Rungs

CafeAI's memory model is a deliberate ladder. Start at the lowest rung that meets your requirements. Graduate only when a rung genuinely falls short.

### Rung 1 — `inMemory()`

```java
app.memory(MemoryStrategy.inMemory());
```

Sessions stored in a `ConcurrentHashMap` in JVM heap. Zero overhead, zero configuration, zero dependencies. Sessions are lost when the JVM exits.

**Use for:** Local development, integration tests, demos.  
**Do not use for:** Anything that needs sessions to survive a restart.

### Rung 2 — `mapped()` — SSD-Backed FFM

```java
app.memory(MemoryStrategy.mapped());
// or with explicit path
app.memory(MemoryStrategy.mapped(Path.of("/var/cafeai/sessions")));
```

Sessions stored as JSON files on disk, mapped into memory via Java's Foreign Function and Memory API. The OS page cache handles hot sessions transparently — frequently accessed sessions are effectively in memory; cold sessions are on disk.

This is the default production recommendation. It combines the speed of memory-mapped I/O with the durability of disk. Sessions survive JVM restarts. A session that was active when the application crashed is fully recoverable on restart — the file is on disk and the next request reads it as if nothing happened.

No network. No infrastructure. No cloud cost. No Redis cluster to operate.

**Use for:** Single-node production deployments.  
**Graduate when:** You need sessions shared across multiple application instances.

### Rung 3 — `chronicle()` — Off-Heap Chronicle Map

```java
app.memory(MemoryStrategy.chronicle(Path.of("/var/cafeai/chronicle")));
```

Chronicle Map is a high-performance off-heap key-value store. Sessions are stored outside the JVM heap — no GC pressure regardless of session count. Chronicle Map handles hundreds of thousands of entries with microsecond access times.

**Use for:** Single-node deployments with very high session volume.  
**Graduate when:** You need cross-instance session sharing.

### Rung 4 — `redis(config)` — Distributed

```java
app.memory(MemoryStrategy.redis(
    RedisConfig.builder()
        .host("redis.internal")
        .port(6379)
        .sessionTtl(Duration.ofHours(8))
        .build()));
```

Sessions stored in Redis with configurable TTL. All application instances share the same session store. Horizontal scaling with session continuity.

**Use for:** Multi-instance deployments, cloud-native architectures.  
**The honest question:** Do you actually have multiple instances? Most applications that default to Redis do not.

### Rung 5 — `hybrid()` — Warm SSD + Cold Redis

```java
app.memory(MemoryStrategy.hybrid(
    MemoryStrategy.mapped(Path.of("/var/cafeai/warm")),
    MemoryStrategy.redis(RedisConfig.of("redis.internal", 6379))));
```

Recent sessions (warm tier) served from local SSD. Sessions not accessed recently (cold tier) promoted from Redis. Idle sessions demoted from SSD to Redis on a configurable schedule.

This gives the latency profile of local SSD with the durability and cross-instance sharing of Redis. The `atlas-inbox` capstone uses a simplified version of this pattern for its claims processing sessions.

**Use for:** High-traffic multi-instance deployments where session read latency matters.

---

## The One-Line Swap

The critical design decision: every rung uses the same API. The application does not know which rung it is on.

```java
// Development
app.memory(MemoryStrategy.inMemory());

// Single-node production
app.memory(MemoryStrategy.mapped());

// Multi-node production
app.memory(MemoryStrategy.redis(RedisConfig.of("redis.internal", 6379)));
```

The route handler is identical in all three cases:

```java
app.post("/chat", (req, res, next) -> {
    var response = app.prompt(req.body("message"))
        .session(req.header("X-Session-Id"))  // same line regardless of rung
        .call();
    res.json(Map.of("response", response.text()));
});
```

Switching rungs is a one-line change at startup registration. No other code changes. The session threading, the history loading, the write-back — all handled by the framework regardless of which strategy is registered.

---

## The FFM API — Why It Matters

The `mapped()` rung is built on Java 21's Foreign Function and Memory API (FFM). Understanding why requires a brief detour into how the OS handles memory-mapped files.

When a file is memory-mapped, the OS creates a mapping between a region of virtual address space and a file on disk. Reading from the mapped region triggers a page fault that loads the corresponding file page into the page cache — a region of physical RAM managed by the OS kernel. Subsequent reads of the same page are served directly from RAM.

The page cache is persistent across JVM restarts. After a JVM crash, the session files are still on disk. When the JVM restarts and maps them again, the first read of each session may come from the page cache (if the kernel hasn't evicted it) or from disk. Either way, the session is fully available.

CafeAI's `MappedMemoryStrategy` uses `Arena.ofShared()` for concurrent access — the same `Arena` can serve multiple virtual threads reading and writing different sessions simultaneously. The OS handles the synchronisation at the page level.

```java
// What MappedMemoryStrategy does internally — simplified
MemorySegment segment = fileChannel.map(
    MapMode.READ_WRITE,
    offset,
    size,
    arena  // shared Arena — safe for concurrent virtual threads
);
byte[] json = segment.toArray(ValueLayout.JAVA_BYTE);
ConversationContext ctx = MAPPER.readValue(json, ConversationContext.class);
```

This is why the FFM API is load-bearing in CafeAI rather than a demo feature. It is the mechanism that makes SSD-backed session memory work correctly under concurrent virtual thread access.

---

## Session TTL and Trimming

Long conversations accumulate context tokens. A session active for an hour may have thousands of tokens of history — more than the model's context window can accommodate, and more expensive to send on every call.

CafeAI's `ConversationContext` trims conversation history when it exceeds a token threshold:

```java
app.memory(MemoryStrategy.mapped()
    .maxTokensPerSession(4_000)  // trim when history exceeds 4k tokens
    .keepLastMessages(4));       // always preserve last 4 messages
```

Trimming removes the oldest messages first, always preserving the most recent exchanges. The last N messages are never trimmed — the current context is always available.

The `meridian-qualify` capstone sets a tighter token budget (2,000) because loan qualification conversations have structured phases — the initial profile submission, the tool calls, the decision. Older turns are less relevant than in an open-ended support conversation.

---

## The `acme-claims` Capstone — Redis in Practice

The `acme-claims` capstone is the first to use `MemoryStrategy.redis()`. Claims sessions need to survive application restarts (a claim filed today should still be accessible tomorrow), and they need to be shareable across AP staff who may be on different application instances.

```java
app.memory(MemoryStrategy.redis(
    RedisConfig.builder()
        .host("localhost")
        .port(6379)
        .sessionTtl(Duration.ofHours(8))  // one work day
        .build()));
```

The swap from `inMemory()` to `redis()` — the only code change from capstone 1 to capstone 3's memory configuration — is one line. Every other line in the application is identical.

This is the demonstration the tiered memory model is designed to enable: the application works at Rung 1 during development, runs at Rung 2 in single-node staging, and graduates to Rung 4 in production without touching any application logic.

---

## When Not to Use Redis

The honest answer to "should I use Redis for my AI application's session memory?" is: only if you have multiple application instances that need to share sessions.

If you run a single application server — even a busy one handling thousands of concurrent users — `MemoryStrategy.mapped()` is faster, cheaper, simpler, and more durable than Redis. Sessions are on disk. They survive restarts. The OS page cache handles hot sessions. There is no network round-trip on every history read.

The Java ecosystem has a cultural bias toward distributed infrastructure that dates from the era when the alternative was writing your own concurrent data structures. The FFM-backed SSD tier is the modern alternative: durable, concurrent, and fast without the operational overhead of a Redis cluster.

Graduate to Redis when you need it. Not before.

---

## Post 6 — RAG

Post 6 covers retrieval-augmented generation — how CafeAI ingests documents, creates embeddings via local ONNX models, stores them in a vector database, and retrieves semantically relevant chunks on every prompt call. The `support-agent` and `acme-claims` capstones demonstrate two different vector store backends and two different retrieval strategies.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
