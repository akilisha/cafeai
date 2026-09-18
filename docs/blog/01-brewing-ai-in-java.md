# Brewing AI in Java — Introducing CafeAI

*Post 1 of 12 in the CafeAI series*

---

There is a conversation the Java ecosystem has been avoiding.

Python got LangChain. JavaScript got Vercel AI SDK. Rust got Candle. Every major language community has produced at least one serious, opinionated answer to the question: how do we build AI-native applications in our ecosystem, for our developers, with our idioms?

Java got Spring AI — which is fine, and useful, and also a good example of what a framework can do when it solves a problem by hiding it. The LLM call disappears behind auto-configuration and injected beans, and a developer can end up unable to say what happens between the bean and the response on their screen. The abstraction works until it doesn't, and when it doesn't, there is little to debug.

CafeAI is a different answer to the same question.

---

## What CafeAI Is

CafeAI is not an invention of anything new. This matters enough that it is the first line of the documentation.

It is a deliberate re-orientation of three proven traditions:

**Java and the JVM** — where enterprise systems already live, where virtual threads and structured concurrency are real, where the Foreign Function and Memory API lets you bind native ML libraries without JNI. The enterprise AI opportunity is not in convincing companies to rewrite their systems in Python. It is in bringing AI capabilities to the Java systems they already run.

**Express.js** — the Node.js framework that taught a generation of developers that HTTP infrastructure should compose rather than configure. `app.get()`, `app.use()`, `app.listen()`. The mental model is so clean that developers who have never written a line of Node.js already understand it. CafeAI mirrors Express pound-for-pound — same method names, same middleware chain, same composability philosophy — but in Java, with AI primitives as first-class citizens alongside HTTP routing.

**Python LangChain** — which established the vocabulary that AI practitioners speak across languages: RAG, agents, tools, guardrails, memory, embeddings. CafeAI speaks this vocabulary natively. A developer moving from LangChain to CafeAI does not learn a new conceptual model. They learn new Java.

The combination is not accidental. It produces a framework that three different audiences can read and understand without documentation:

```java
var app = CafeAI.create();

app.ai(OpenAI.of("gpt-4o"));
app.memory(MemoryStrategy.mapped());
app.guard(GuardRail.pii());
app.system("You are a helpful customer service agent for Acme Corp.");

app.post("/chat", (req, res, next) -> {
    res.json(Map.of(
        "response", app.prompt(req.body("message"))
                       .session(req.header("X-Session-Id"))
                       .call()
                       .text()
    ));
});

app.listen(8080);
```

A Java developer who has never touched Gen AI reads this and understands every line. An Express developer who has never touched Java recognises the structure. A Python LangChain developer recognises the concepts. That is three audiences, zero confusion, and no framework-specific vocabulary to learn before you can do anything useful.

---

## The Problem It Solves

Every AI application has the same hard problems. Call a language model. Keep track of the conversation. Retrieve relevant information from a knowledge base. Call external tools. Enforce safety rules. Observe what happens in production. Scale to real traffic.

In most frameworks, these concerns live in different places. The LLM call is in one class, the memory management is in another, the guardrails are middleware bolted on afterward, the observability is a separate concern someone else configured. The developer assembles these pieces and calls the result "architecture."

CafeAI treats these as a pipeline of composable layers. The HTTP side is the Express model: middleware you register in order. The AI side is a fixed pipeline behind `app.prompt()`, `app.vision()` and `app.audio()`, and the layers in it are the ones you register with `app.guard(...)`, `app.rag(...)`, `app.memory(...)` and `app.observe(...)`:

```
Incoming HTTP request
    ↓
[ HTTP middleware ]         cors, rate limit, body parsing, your own authentication
    ↓
[ route handler ]           calls app.prompt(...), which runs:
        ↓
        [ PRE_LLM guardrails ]     jailbreak, injection, PII, topic boundary
        [ semantic cache ]         if enabled
        [ session memory read ]    history for this session ID
        [ RAG retrieval ]          if a vector store is registered
        [ LLM call ]               token budget, retry and observability wrap this step
        [ POST_LLM guardrails ]    PII, toxicity, secrets, prompt leaks
        [ session memory write ]
    ↓
Response
```

Every layer here is independently explainable, independently testable and independently replaceable. You can leave out RAG and the rest still works. You can swap `ObserveStrategy.console()` for `ObserveStrategy.otel()` and nothing else changes. You can add a guardrail without touching the LLM call.

This is not a novel idea. It is the reason Express became the dominant Node.js framework a decade ago. The middleware pattern is the right abstraction for composable request processing — and AI requests are request processing, just with a language model in the middle.

---

## The Name

**Cafe** → a coffee shop, instantly recognisable as *Java*.  
**AI** → the technology we are introducing.  
**CafeAI** → *"kaf-ai"* — a natural coming together.

It is also, less literally, where you go to think. Developers write code in cafes. Ideas happen in cafes. The name is earned.

---

## What Java Gives CafeAI

Three things in the modern JVM are load-bearing here, not demos.

**Virtual threads** handle every request: Helidon SE runs each request on one. LLM calls are I/O-bound and spend most of their time waiting for the API, which is the case virtual threads make cheap. A JVM can hold thousands of in-flight calls without a thread-pool bottleneck, and the code stays plain blocking code.

**The Foreign Function and Memory API (FFM)** backs the `mapped()` session-memory tier. Rather than send conversation history to Redis on every turn, CafeAI keeps each session in a file under a directory you choose and maps it into off-heap memory. Sessions survive JVM restarts, cost no network round trip and need no infrastructure.

**The Vector API** is what lets `Jlama` run a model inside the JVM, with no server and no API key. It needs Java 23 or later and a few JVM flags (the `Jlama` Javadoc lists them), which is why CafeAI's toolchain floor is Java 23. CI builds and tests on Java 23.

Local embeddings (`EmbeddingProvider.local()`) use LangChain4j's bundled all-MiniLM-L6-v2 model and run in-process the same way.

---

## The Tiered Memory Model

One specific architectural decision is worth calling out before the rest of the series, because it shapes almost every application you will build with CafeAI.

Most AI tutorials default to Redis for session memory. Redis is excellent infrastructure. It is also frequently unnecessary.

CafeAI's memory model is a four-rung ladder:

```
Rung 1 → inMemory()     JVM heap — dev and testing only
Rung 2 → mapped()       SSD-backed FFM MemorySegment — single-node production
Rung 3 → redis(config)  Redis — distributed, multi-instance
Rung 4 → hybrid()       Warm SSD + cold Redis — both
```

The insight is that `mapped()` — the SSD-backed tier — is enough for many single-node deployments. It avoids a network round trip, needs no infrastructure, and keeps sessions across JVM restarts because they are on disk. Redis becomes the right choice when you need state shared across multiple application instances.

The swap between rungs is one line:

```java
// Development
app.memory(MemoryStrategy.inMemory());

// Single-node production
app.memory(MemoryStrategy.mapped());

// Multi-node production
app.memory(MemoryStrategy.redis(RedisConfig.of("redis.internal", 6379)));
```

The rest of the application is identical. The memory strategy is registered once and the pipeline handles the rest.

---

## Configuration, Not Magic Numbers

One more decision is worth calling out this early, for the same reason as the memory model above: it shapes every application you build, and getting it wrong is invisible until someone goes looking for it.

Magic variables inside a codebase are a major source of real, silent, and painful developer experience. They typically come from immutable, hardcoded values, or from values sourced unexpectedly from undocumented places. Either way, they constrain what the application — or framework — can do to only the range those variables happen to permit: a hardcoded timeout, retry count, or memory window that's wrong for your workload just fails or misbehaves, with no lever anywhere to fix it — no setter, no environment variable, not even a line of documentation admitting it exists.

CafeAI's answer is a self-documenting configuration key, declared once, right next to the code that reads it — not in a central file that can drift out of sync with what's actually read:

```java
static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
        "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60),
        "Timeout for a single LLM chat call, any provider");

Duration timeout = AppConfig.load().get(CHAT_TIMEOUT);
```

Declaring a `ConfigKey` costs nothing extra — it lives in `cafeai-core`, which every module already depends on. Without anything more, `get()` returns the coded default unconditionally: 60 seconds, exactly the behaviour the value already had as a bare constant. Add the optional `cafeai-config` module, and the same call resolves — in order — a system property, an environment variable, an external file, or `application.yaml` on the classpath, using dotted names the way Spring or Helidon would, mapped to environment variables by Helidon Config itself rather than any spelling CafeAI invents:

```yaml
# application.yaml
cafeai:
  chat:
    timeout: 90s
```

The same boundary that shapes the rest of this framework applies here too: config supplies values, it never wires capabilities. A key can say what a timeout is; it can never cause `app.ai(...)` or `app.vectordb(...)` to register anything on its own. Application code stays the only thing that calls `app.*` — configuration only ever answers a question the code explicitly asked.

---

## What This Series Covers

This is Post 1 of 12. Each subsequent post covers one capability of the framework, anchored to a runnable capstone application:

| Post | Topic | Capstone |
|------|-------|----------|
| 2 | The middleware pattern and how it applies to AI | support-desk |
| 3 | Your first LLM call without Spring Boot | support-desk |
| 4 | Prompt engineering in Java | support-desk, meridian-qualify |
| 5 | Context memory without the cloud tax | meridian-qualify, acme-claims |
| 6 | Building a RAG pipeline in Java | support-desk, acme-claims |
| 7 | Tool use — giving the AI actions to take | support-desk, meridian-qualify, acme-claims |
| 8 | Ethical guardrails as middleware | meridian-qualify, acme-claims |
| 9 | Vision and audio in Java | invoice-processor |
| 10 | Structured output — typed LLM responses | invoice-processor |
| 11 | Production-grade AI — budgets, retries, observability, cluster incident response | invoice-processor, cluster-sentinel |
| 12 | The capstone series — five applications and what each found | all five capstones |

Every post links to running code, and the code samples are checked against the real API. Where the framework has a limit, the post says so.

---

## Getting Started

CafeAI is on Maven Central. In your project's `build.gradle`:

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.4.0'
}
```

And the smallest possible CafeAI application:

```java
var app = CafeAI.create();
app.ai(OpenAI.of("gpt-4o"));

var response = app.prompt("What is the capital of France?").call();
System.out.println(response.text());  // Paris
```

That is it. No annotations. No configuration files. No dependency injection container. The call goes through LangChain4j to the provider's own client, and you can see every step of it.

Post 2 explains why everything in CafeAI is a middleware — and why that turns out to be the right answer for AI applications, for the same reasons it was the right answer for HTTP applications a decade ago.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
