# Tool Use in Java — Giving the AI Actions to Take

*Post 7 of 12 in the CafeAI series*

---

A language model without tools is a very expensive search engine. It retrieves and synthesises information well, but it cannot take action. It cannot check the current status of a GitHub issue, look up a vendor's contracted rate, verify a policy is active, or approve a payment. It can only report what it knows from training data and retrieved context.

Tools change this. A tool is a Java method the LLM can call — a bridge between the model's reasoning and the application's actual capabilities. The model decides when to call a tool, what arguments to pass, and how to use the result. The developer decides what tools exist, what they return, and how their output affects the pipeline.

CafeAI registers tools with a single annotation:

```java
public class VendorContractLookup {

    @CafeAITool("Look up a vendor's contracted rate by vendor ID and service category. " +
                "Returns the contracted amount and tolerance percentage.")
    public String getContractedAmount(String vendorId, String category) {
        // call the ERP system
        return erpClient.getContract(vendorId, category).toJson();
    }
}

// Register at startup
app.tool(new VendorContractLookup());
```

The annotation value is the tool description — the natural language instruction the LLM uses to decide when and how to call the method. The method signature defines the arguments. The return value is passed back to the LLM as a tool result.

---

## How Tool Calling Works

When the LLM receives a prompt, it may decide to call a tool before responding. The process:

1. The LLM generates a tool call request: `{ "tool": "getContractedAmount", "args": { "vendorId": "VND-1005", "category": "hardware" } }`
2. CafeAI's `ToolRegistry` deserialises the request, locates the registered method, and invokes it
3. The tool result is added to the conversation and the LLM generates its next response — which may be another tool call or the final answer

This loop continues until the LLM produces a final text response. Most tool-calling scenarios require 1-3 tool calls. Complex agent scenarios can require more.

The developer does not orchestrate this loop. CafeAI's `LangchainBridge` handles the tool execution cycle. The developer writes the tools and registers them; the framework handles the rest.

---

## Tool Trust Levels

Not all tools are equal. A tool that reads from a database has a different trust profile than a tool that calls an external API. An internal tool written by the application team is different from an MCP server tool from a third party.

CafeAI makes trust levels explicit:

```java
// INTERNAL — Java method, application team owns it, full trust
app.tool(new VendorContractLookup());    // registered with INTERNAL trust

// EXTERNAL — MCP server, third-party, limited trust
app.mcp(McpServer.github());             // registered with EXTERNAL trust
```

Tools registered with `app.tool()` are INTERNAL — they run in the same JVM, with the same security context as the application. Tools registered via `app.mcp()` are EXTERNAL — they run in a separate process, accessed via the MCP protocol, with a different trust level.

The trust level is recorded in the `ToolDefinition` and visible in observability output:

```
18:37:46 [main] INFO  ToolRegistry - Invoking tool 'getContractedAmount' [INTERNAL] with args: {"vendorId":"VND-1005"}
```

This makes tool invocations auditable — every call records which tool was invoked, at what trust level, with what arguments, and what was returned.

---

## Tools as Policy Enforcers

The most important pattern in CafeAI's tool model is not enrichment — it is enforcement.

In the `meridian-qualify` capstone, the credit check tool returns an authoritative result:

```java
@CafeAITool("Check credit eligibility for a loan applicant. " +
             "Returns ELIGIBLE, MARGINAL, or INSUFFICIENT_CREDIT " +
             "based on the applicant's credit profile.")
public String checkCreditEligibility(String applicantId, double requestedAmount) {
    CreditResult result = creditBureau.check(applicantId, requestedAmount);
    return result.toJson();  // {"status": "INSUFFICIENT_CREDIT", "score": 580}
}
```

When the tool returns `INSUFFICIENT_CREDIT`, the LLM cannot approve the loan. The tool result is factual ground — the model cannot invent a different credit score. The tool enforces the business rule that the model must respect.

This is the key insight: tools do not just give the model more information. They give the model authoritative information that constrains what it can honestly say. A model that hallucinated a credit approval despite a tool returning `INSUFFICIENT_CREDIT` would be producing a factually incorrect response. The tool result is the source of truth.

The same pattern appears in all four capstones:

- `support-agent` — GitHub tool returns authoritative issue status; model cannot invent it
- `meridian-qualify` — credit check tool returns authoritative eligibility; model cannot override it
- `acme-claims` — policy lookup tool returns active/inactive status; model cannot approve inactive claims
- `atlas-inbox` — vendor contract tool returns contracted amount; model cannot approve a discrepancy it didn't detect

---

## The `@CafeAITool` Annotation

The annotation is the tool contract. Its value is the description — the most important part:

```java
// Too vague — model doesn't know when or how to call this
@CafeAITool("Look up vendor information")
public String getVendor(String id) { ... }

// Better — model knows exactly when to call it and what to pass
@CafeAITool("Look up a vendor's name, contact email, and contract type " +
             "by their vendor ID (e.g. VND-1005). " +
             "Call this before checking contracted amounts.")
public String lookupVendorById(String vendorId) { ... }
```

Tool description quality directly affects tool call accuracy. A vague description produces incorrect tool invocations. A specific description — what it returns, when to call it, what format the arguments should be in — produces reliable invocations.

The method signature defines the argument schema. CafeAI's `ToolRegistry` reflects on the method to extract parameter names and types, which are included in the tool specification sent to the LLM.

---

## Error Handling

Tool exceptions do not propagate to the caller. If a tool throws, `ToolRegistry` catches the exception and returns `"ERROR: <message>"` as the tool result. The LLM receives the error message and can respond accordingly — typically by explaining that the lookup failed and suggesting the user try again or contact support.

```java
@CafeAITool("Look up claim status by claim number.")
public String getClaimStatus(String claimNumber) {
    try {
        return claimsSystem.getStatus(claimNumber).toJson();
    } catch (ClaimNotFoundException e) {
        return "{\"error\": \"Claim not found: " + claimNumber + "\"}";
    }
    // RuntimeExceptions that escape here are caught by ToolRegistry
    // and returned as "ERROR: <exception message>"
}
```

Returning structured error JSON (rather than letting exceptions propagate) gives the LLM better information to work with. `{"error": "Claim not found: 12345"}` is more useful than `ERROR: ClaimNotFoundException: 12345`.

---

## Multiple Tools

An application can register multiple tool classes. CafeAI scans each class for `@CafeAITool`-annotated methods:

```java
// atlas-inbox registers three tool classes at startup
app.tool(new VendorContractLookup());   // 2 tools: lookupVendorByName, getContractedAmount
app.tool(new DiscrepancyRecorder());    // 1 tool:  recordDiscrepancy
app.tool(new InvoiceApprover());        // 1 tool:  approveInvoice
```

The LLM has access to all four tools. During reconciliation, a typical tool-calling sequence looks like:

```
1. lookupVendorByName("Liberty Fastener Inc.")
   → {"vendorId": "VND-1005", "name": "Liberty Fastener Company", ...}

2. getContractedAmount("VND-1005", "PO106068")
   → {"contractedAmount": 1400.00, "tolerancePct": 5.0, ...}

3. approveInvoice("VND-1005", "0212164-1", "1353.50", "PO106068")
   → "[APPROVED] vendor=VND-1005 | invoice=0212164-1 | amount=1353.50"
```

The LLM decides the sequence. The developer defines the tools and registers them. The framework executes the loop.

---

## Tool Results and Session Memory

Tool calls and results are stored in session memory as part of the conversation history. On the next turn, the model knows what tools were called and what they returned in previous turns — it does not re-call a tool it already called unless the situation has changed.

This is relevant for multi-step workflows where the first turn establishes facts (vendor lookup, contract retrieval) and subsequent turns reason about them (discrepancy analysis, reply composition). The tool results from turn 1 are in the context for turn 2 without re-invoking the tools.

---

## MCP — External Tool Servers

The Model Context Protocol (MCP) is a standard for exposing tools from external processes. CafeAI supports MCP servers alongside internal tools:

```java
// Internal tool — runs in-process, INTERNAL trust
app.tool(new GitHubTools());

// MCP server — runs out-of-process, EXTERNAL trust
app.mcp(McpServer.github());   // connects to github MCP server
app.mcp(McpServer.connect("http://my-tools-server:8080"));
```

The distinction matters operationally: an INTERNAL tool failure is an application exception. An EXTERNAL MCP tool failure is a network call failure — different error semantics, different retry behaviour, different trust assumptions.

MCP tools follow the same `@CafeAITool` registration from the LLM's perspective — the model cannot distinguish an internal tool from an MCP tool. The distinction is in the trust level recorded in observability and in how failures are handled.

---

## POST_LLM Guardrails on Tool Output

CafeAI's POST_LLM guardrails fire after the tool calling loop completes — on the assembled final response, not on each individual tool result. This means PII guardrails catch sensitive data that appears in the response text even if it arrived via a tool call.

```java
// This guardrail fires on the final response text —
// including content assembled from tool results
app.guard(GuardRail.pii());
```

If a tool returns a customer's SSN as part of its response and the LLM includes it in its reply, the PII guardrail catches it before the caller receives it. Tool use does not create a bypass around the safety pipeline.

This was added in ROADMAP-14 Phase 11 — an explicit gap closed after the `atlas-inbox` capstone demonstrated that tool-heavy pipelines needed post-tool-loop guardrail coverage.

---

## Post 8 — Guardrails

Post 8 covers the full guardrail system — PII scrubbing, jailbreak detection, regulatory compliance (FCRA, ECOA, HIPAA), bias detection, topic boundary enforcement, and how PRE_LLM and POST_LLM positions work together to create a safety envelope around every AI call.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
