# Prompt Engineering in Java — Templates, System Prompts, and the API Vocabulary

*Post 4 of 12 in the CafeAI series*

---

Prompt engineering gets a bad reputation in typed language communities. The name sounds like the opposite of engineering — something fluid and intuitive that resists the structure that Java developers are paid to impose. The reality is that production AI systems need the same discipline applied to prompts that they apply to everything else: typed contracts, reusable components, testable units.

CafeAI provides three mechanisms for structured prompt management: system prompts for persona, named templates for reusable patterns, and the fluent `PromptRequest` chain for call-specific overrides. This post covers all three, with examples drawn from the `support-agent` and `meridian-qualify` capstones.

---

## System Prompts — The AI's Persona

The system prompt is the instruction that defines who the AI is. It runs before every user message, setting the context for the entire conversation. Getting it right is the single highest-leverage prompt engineering task in any application.

In CafeAI, the system prompt is registered once at startup:

```java
app.system("""
    You are a customer support assistant for Helios API.

    Your responsibilities:
    - Answer questions about the Helios API surface, authentication,
      rate limits, webhooks, and SDK usage
    - Look up GitHub issue status when developers ask about specific issues
    - Troubleshoot integration problems using the Helios documentation

    Your boundaries:
    - Do not discuss topics unrelated to Helios
    - Do not make promises about roadmap items or feature requests
    - Do not share API keys or credentials under any circumstances
    - When you do not know something, say so clearly

    Tone: technically precise, concise, helpful. Assume the user is a developer.
    """);
```

Three structural elements make this system prompt effective:

**Responsibilities** tell the model what it should do. Explicit and specific — not "be helpful" but "look up GitHub issue status when developers ask about specific issues."

**Boundaries** tell the model what it must not do. These reinforce the guardrails that CafeAI enforces mechanically — the topic boundary guardrail blocks off-topic questions at the infrastructure layer, and the system prompt reinforces the intent at the model layer.

**Tone** tells the model how to communicate. A one-line tone instruction measurably affects response style. "Assume the user is a developer" produces a different register than the default.

---

## Per-Call System Prompt Overrides

Sometimes a single call needs a different persona than the application default. CafeAI supports per-call overrides without affecting the application-level system prompt:

```java
// Application-level system prompt — registered at startup
app.system("You are a customer support assistant for Helios API...");

// Per-call override — affects only this call
var response = app.prompt("Translate the following to Spanish: " + text)
    .system("You are a professional Spanish translator. " +
            "Translate accurately and preserve technical terms.")
    .call();
```

The `.system()` override on the `PromptRequest` replaces the application-level prompt for that call only. The next call uses the application-level system prompt again. This enables specialised sub-tasks — classification, translation, formatting — within a larger application without registering multiple CafeAI instances.

---

## Named Templates — Reusable Prompt Patterns

Complex applications have recurring prompt patterns: extract structured data from this input, classify this text into one of these categories, generate a response following this format. Hardcoding these inline creates the same problems as hardcoding SQL — duplication, drift, no single place to update.

CafeAI named templates give prompt patterns the same treatment as any other reusable component:

```java
// Register at startup
app.template("sentiment-analysis", """
    Analyse the sentiment and urgency of the following vendor email.
    Respond with ONLY a valid JSON object — no explanation, no markdown.

    JSON schema:
    {
      "tone":             "<PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT>",
      "urgency":          "<HIGH|MEDIUM|LOW>",
      "escalate":         <true|false>,
      "recommendedAction": "<one sentence describing the recommended next step>"
    }

    Email:
    ---
    {{emailBody}}
    ---
    """);

app.template("vendor-reply", """
    Draft a professional reply from {{senderName}} to the following
    vendor email. The reply should:
    - Acknowledge receipt of the email
    - {{action}}
    - Maintain a professional, courteous tone

    Original email:
    ---
    {{emailBody}}
    ---
    """);
```

Templates use `{{variable}}` interpolation. Calling a named template looks like:

```java
// Render and call in one step
var result = app.prompt("sentiment-analysis", Map.of(
    "emailBody", email.body()
)).call();

// Or render separately for inspection
String rendered = app.template("vendor-reply").render(Map.of(
    "senderName", "AP Processing Team",
    "action",     "inform the vendor of the identified discrepancy",
    "emailBody",  email.body()
));
var response = app.prompt(rendered).call();
```

Templates are validated at registration time — `{{variable}}` references that don't appear in the data map throw a `TemplateException`. This catches missing variable errors at the template call site rather than producing a garbled prompt at runtime.

---

## Strict vs Lenient Rendering

The default template renderer leaves unreplaced variables intact — `{{missingVar}}` appears in the output if no value is provided. This is lenient mode: useful for optional variables and partial rendering.

For prompts where every variable must be supplied, strict mode throws on any missing variable:

```java
// Strict — throws TemplateException if any variable is missing
String rendered = app.template("sentiment-analysis")
    .renderStrict(Map.of("emailBody", email.body()));

// Lenient (default) — leaves {{missingVar}} in output
String rendered = app.template("sentiment-analysis")
    .render(Map.of("emailBody", email.body()));
```

The `meridian-qualify` capstone uses strict rendering for all its templates — a loan qualification prompt that is missing the applicant's income figure should fail immediately, not proceed with `{{income}}` in the text.

---

## The PromptRequest Fluent Chain

Every `app.prompt()` call returns a `PromptRequest` — a fluent builder that collects configuration before execution. The full chain:

```java
var response = app.prompt("Analyse this loan application")
    .system("You are a loan qualification assistant...")  // override system prompt
    .session("applicant-A7F2")                           // thread session memory
    .returning(QualificationDecision.class)              // expect structured output
    .call(QualificationDecision.class);                  // execute and deserialise
```

Each method is optional and returns `this` for chaining. Only `.call()` executes.

The key design decision: execution is deferred. Building the chain costs nothing — no LLM calls, no memory reads, no network activity. Everything happens when `.call()` is invoked. This makes the request chain testable: you can build a `PromptRequest` and inspect its state before executing it.

---

## Structured Output — The `.returning()` Pattern

The most important fluent method is `.returning(Class<T>)`. It declares that the LLM response should be deserialised to a typed Java record or POJO.

Without structured output:

```java
// Old pattern — manual JSON parsing on every call
String raw = app.prompt(sentimentPrompt).call().text();
String clean = raw.replaceAll("(?s)```json\\s*", "")
                  .replaceAll("(?s)```\\s*", "").trim();
SentimentResult result = MAPPER.readValue(clean, SentimentResult.class);
```

With structured output:

```java
// New pattern — one line
SentimentResult result = app.prompt(sentimentPrompt)
    .returning(SentimentResult.class)
    .call(SentimentResult.class);
```

Internally, `SchemaHintBuilder` reflects on `SentimentResult` and appends a JSON schema example to the prompt. `ResponseDeserializer` strips any markdown fences from the response and parses it. The developer writes neither.

The `atlas-inbox` capstone uses this pattern four times: `SentimentResult`, `AttachmentClassification`, `InvoiceData`, and `ReconciliationResult`. Each is a plain Java record. Each is populated by one `.call()` line. The 40 lines of boilerplate parsing that existed in the original version are gone.

---

## Prompt Engineering Principles

Three principles that appear consistently across the four capstones:

**Be explicit about schema.** The `.returning(Class)` mechanism handles this mechanically, but the principle applies to manual prompts too. "Respond with ONLY a valid JSON object" is more reliable than "respond in JSON format" — the word "only" reduces prose-before-JSON responses significantly.

**Separate responsibilities from boundaries.** A system prompt that lists what the model should do and separately what it must not do outperforms one that mixes the two. The `meridian-qualify` system prompt has explicit `FCRA boundaries:` and `ECOA boundaries:` sections that mirror the regulatory guardrails registered in the pipeline. The model and the infrastructure enforce the same constraints from different positions.

**Personas are contracts.** A system prompt that says "you are a professional loan qualification assistant" establishes a contract the model generally honours. Specificity strengthens the contract: "you are a professional loan qualification assistant for Meridian Home Loans. You do not approve or decline loans — you recommend decisions for human review" gives the model a clearer role than "you are an assistant."

---

## Testing Prompts

Prompts should be tested like any other code. CafeAI's template system makes this straightforward:

```java
@Test
void sentimentTemplate_rendersEmailBodyCorrectly() {
    String rendered = app.template("sentiment-analysis")
        .render(Map.of("emailBody", "Please pay immediately or face legal action."));

    assertThat(rendered).contains("Please pay immediately");
    assertThat(rendered).contains("JSON schema");
    assertThat(rendered).doesNotContain("{{emailBody}}");
}

@Test
void sentimentTemplate_strictMode_throwsOnMissingVariable() {
    assertThatThrownBy(() ->
        app.template("sentiment-analysis").renderStrict(Map.of()))
        .isInstanceOf(TemplateException.class)
        .hasMessageContaining("emailBody");
}
```

Template rendering is pure string manipulation — no LLM call needed. Tests run in milliseconds. The prompt content is validated in CI before any API calls are made.

---

## What Post 5 Covers

Post 5 covers context memory — how CafeAI manages conversation history, why the SSD-backed tier handles most production workloads without Redis, and the complete tiered memory model from JVM heap to distributed cache.

The memory model is the most underappreciated part of CafeAI's architecture. Post 5 makes the case for it.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
