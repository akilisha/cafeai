# Tool Use in Java — Giving the LLM Actions to Take

*Post 7 of 12 in the CafeAI series*

---

Retrieval gives the model information. Tools give it actions: look up an order, check an issue's status, calculate a debt-to-income ratio. The model decides when a tool is needed, the framework calls the Java method, and the result goes back into the conversation.

LangChain4j already does all of that. Its `AiServices` owns the tool schema, the dispatch and the calling loop, and CafeAI does not reimplement any of it. What `cafeai-aiservices` adds is the part a web application needs around it: a name, an HTTP-friendly way to get the right conversation, and the CafeAI pieces the application has already configured.

---

## Registering an agent

An agent is a plain Java interface. LangChain4j implements it; CafeAI wires it up.

```java
public interface SupportAssistant {
    String answer(@UserMessage String question);
}
```

Registration happens once, at startup, before `listen()`:

```java
app.agent("support", SupportAssistant.class)
    .system("You are a support specialist for the Helios connection pool.")
    .tool(new GitHubTools());
```

Resolution happens inside a route handler, and takes the session:

```java
app.post("/chat", (req, res, next) -> {
    var agent = app.agent("support", SupportAssistant.class,
                          req.header("X-Session-Id"));
    res.json(Map.of("answer", agent.answer(req.body("message"))));
});
```

`app.agent(name, Class, sessionId)` returns the proxy for that agent and that session, building it the first time and reusing it after. The route handler never manages a proxy's lifetime.

---

## A tool

A tool is any Java object with `@Tool`-annotated methods. This is the smallest one in the `support-desk` capstone:

```java
public class GitHubTools {

    @Tool("Fetch the current status of a Helios GitHub issue by its number. " +
          "Returns the issue title, state (open/closed), and latest comment.")
    public String getIssueStatus(String issueNumber) {
        // ... GET https://api.github.com/repos/helios-pool/helios/issues/{issueNumber}
    }

    @Tool("Search open Helios GitHub issues by keyword. " +
          "Use this when the user describes a problem but doesn't know the issue number.")
    public String searchIssues(String keyword) { /* ... */ }
}
```

(The capstone's methods return canned data so it runs without a network; the shape is what matters.)

The description string is the interface the model sees. There is no dispatch code in the application. A user who asks "is #156 fixed?" causes the model to call `getIssueStatus("156")` and answer from the result. A user who asks "what is a connection pool?" causes no tool call at all. A user who says "my pool times out" and gives no issue number is the reason `searchIssues` says when to use it.

Write descriptions for the model, not for a colleague: say what the tool returns, and say when to choose it over the others.

---

## Several tools, one agent

`meridian-qualify` gives one agent three tools: `verifyLendingFootprint`, `calculateDTI` and `estimateMonthlyPayment`. The model picks which to call and in what order for a single request.

The capstone goes further than suggesting an order. Its system prompt sets a protocol (footprint first, then DTI, then payment), and the footprint tool's description says its result is authoritative. When the state is outside the lending footprint the tool's answer tells the model to stop:

```
FOOTPRINT CHECK RESULT: DECLINED. Meridian Home Loans does not lend in TX. ...
Stop all assessment immediately — do not calculate DTI or payments.
```

The pattern is worth keeping in mind for any regulated domain: a tool that computes the answer constrains what the model can say. The model cannot invent a debt-to-income ratio when the arithmetic came back from a method. The same idea limits how much of the risk is left to prompting.

---

## What the binding adds

Because the agent is registered with CafeAI, it picks up what the application has already configured. Everything here is optional; an agent with nothing but `.tool(...)` works.

```java
app.agent("qualify", QualificationAgent.class)
    .system(SYSTEM_PROMPT)                 // the agent's instructions
    .model(Anthropic.of("claude-sonnet-5")) // else the app's default from app.ai(...)
    .tool(new QualificationTools())
    .memory(MemoryStrategy.inMemory())     // else the app's default from app.memory(...)
    .rag(Retriever.semantic(3))            // else the app's from app.rag(...)
    .guard(GuardRail.regulatory().ecoa())  // in addition to the app's app.guard(...) rails
    .configure(b -> b.toolProvider(mcpToolProvider)); // the raw LangChain4j builder
```

- **Session memory.** The session id you pass at resolution is the conversation id, and the history is kept by whichever `MemoryStrategy` the app or the agent uses, so an agent's conversation survives a restart if the strategy does. The window is the last 20 messages by default (`cafeai.agent.memory.window`).
- **Retrieval.** With `app.rag(...)`, `app.vectordb(...)` and `app.embed(...)` configured, the agent retrieves from the same knowledge base as `app.prompt()`.
- **Guardrails.** Every guardrail registered with `app.guard(...)` applies to the agent, and so does anything added with the agent's own `.guard(...)`. Input rails run before the model is called. Post 8 covers them.
- **Observability.** Each invocation is an `invoke_agent <name>` span under OpenTelemetry, or an "Agent Invocation" block on the console strategy. Token counts are not reported on this path: LangChain4j owns the loop, so CafeAI sees the invocation, not each model call inside it.
- **`.configure(...)`** hands you LangChain4j's `AiServices` builder after CafeAI has applied its own settings, for everything CafeAI does not abstract: an MCP tool provider, a per-session memory provider, output parsers. CafeAI does not wrap MCP; LangChain4j's MCP support attaches here.

---

## Several agents, one workflow

A single agent with several tools is still one system prompt trying to be a medical assistant, a legal reviewer and a systems technician at once — the instructions dilute as they cover more ground. `cafeai-agentic` binds a different LangChain4j library, `langchain4j-agentic`, built for composing several specialist agents into one workflow with its own shared state (an `AgenticScope`). LangChain4j owns the sequencing, delegation and that shared state; CafeAI's job is the same job it did for the single agent above — give the workflow the app's model, guardrails and observability, without repeating the wiring on every specialist.

```java
public interface MedicalExpert {
    @Agent(name = "medicalExpert", outputKey = "medicalAdvice")
    String assess(String question);
}

public interface LegalExpert {
    @Agent(name = "legalExpert", outputKey = "legalAdvice", summarizedContext = "medicalExpert")
    String advise(String question);
}

MedicalExpert medicalExpert = CafeAgentic.agentBuilder(app, MedicalExpert.class).build();
LegalExpert legalExpert     = CafeAgentic.agentBuilder(app, LegalExpert.class).build();

Consultation workflow = AgenticServices.sequenceBuilder(Consultation.class)
    .subAgents(medicalExpert, legalExpert)
    .outputKey("legalAdvice")
    .build();
```

`CafeAgentic.agentBuilder` does for each specialist exactly what `app.agent(...)` did for the single agent above. What it deliberately does not do is wrap `sequenceBuilder` itself, or LangChain4j's other composers (`parallelBuilder`, `loopBuilder`, `conditionalBuilder`, `supervisorBuilder`): composing already-wired specialists carries their guardrails and observability with them, and CafeAI has not confirmed those composers share `AgentBuilder`'s own model/guardrail surface closely enough to pre-wire without guessing at it.

**The problem this is really for:** route a follow-up question from one specialist to another and the second one starts from nothing — it never saw the first conversation. `@Agent(summarizedContext = "medicalExpert")` on `LegalExpert` is the fix: a real, LLM-generated summary of `medicalExpert`'s conversation is pulled into `legalExpert`'s own context before it answers. This is already built into `langchain4j-agentic`; `cafeai-agentic` does not implement its own summarizer.

**Choosing the specialist, not fixing the order:** `sequenceBuilder` always runs every agent, in order. When which specialist fits depends on the request, `supervisorBuilder` decides at runtime instead:

```java
Consultation supervisor = AgenticServices.supervisorBuilder(Consultation.class)
    .chatModel(CafeAgentic.chatModel(app))
    .subAgents(medicalExpert, legalExpert, technicalExpert)
    .build();
```

`supervisorBuilder` is a composer too, so it needs its own `.chatModel(...)` — `CafeAgentic.chatModel(app)` is the app's registered model, exposed directly for exactly this, since the composers aren't pre-wired the way a single agent is.

**Watching it run:** extend `MonitoredAgent` on the workflow's root interface and every invocation — inputs, output, timing, token counts — is recorded. `langchain4j-agentic` has no HTML report for this (that tooling is Quarkus Dev UI, not the core library); `CafeAgenticMonitor.route(workflow.agentMonitor())` gives it a JSON one instead, mounted like any other route:

```java
app.get("/agentic/monitor", CafeAgenticMonitor.route(workflow.agentMonitor()));
```

A few things worth knowing before composing agents this way:

- **A parameter name is a contract across the whole chain.** Each agent's parameters resolve against the shared scope, which holds only the entry call's own parameter names plus whatever earlier agents wrote under their `outputKey`. Name a later agent's parameter anything else and the call fails at invocation, not at build time.
- **A supervisor's entry parameter must be named `request`.** Its planner reads a fixed scope key by default, unlike `sequenceBuilder`'s name-matching.
- **Choosing well and arguing well are different asks of the model.** Building the supervisor example, a small model reliably picked the right specialist for a question and still failed to construct that specialist's call arguments correctly — it echoed the agent's own description back instead of a real value. Routing quality and argument-construction quality both need checking, not just one.

---

## Limits worth knowing

**The agent's retrieved documents are not screened.** With `app.prompt()`, `GuardRail.promptInjection()` also checks each retrieved document and drops one that carries an injected instruction. On the agent path, retrieval belongs to LangChain4j and CafeAI does not intercept it.

**A tool's result is an untrusted input.** Whatever a tool returns goes into the model's context, and a tool that fetches text a third party wrote (an issue body, an email, a web page) is a way for that party to speak to your model. The input guardrails see the user's message and the output guardrails see the final answer; neither sees the tool result in between. Keep tools narrow, return only the fields the model needs, and do not give an agent a tool whose side effects you would not let an anonymous user trigger.

**The model still chooses.** A tool description asks the model to call the tool; it does not force the call. A protocol in a prompt is a request, and a model can skip a step, so anything that must happen belongs in the tools and in code that checks the outcome.

---

## Test a tool like any other Java

A tool is a plain object, so it tests without a model. For example:

```java
@Test
void aStateOutsideTheFootprintIsDeclined() {
    var tools = new QualificationTools();
    assertThat(tools.verifyLendingFootprint("TX")).contains("DECLINED");
    assertThat(tools.verifyLendingFootprint("wi")).contains("APPROVED");
}
```

The logic that has to be right (eligibility, arithmetic, permissions) belongs in that method, where a unit test can pin it, rather than in the prompt, where it can only be hoped for.

---

Post 8 covers guardrails: the checks that run on every prompt, vision, audio and agent call, and what each one can and cannot catch.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
