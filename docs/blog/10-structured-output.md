# Structured Output — Typed LLM Responses, No Parser Required

*Post 10 of 12 in the CafeAI series*

---

Language models produce text. Applications need data.

The gap between these two facts is responsible for an enormous amount of boilerplate code in AI applications. The developer writes a prompt that asks for JSON. The model produces JSON. Sometimes it wraps it in markdown fences. Sometimes it adds a prose introduction. Sometimes it includes a closing remark. The developer writes a parser that strips all of this, handles the edge cases, catches the `JsonParseException` when the model decides to produce something unexpected, and calls the result "structured output."

The `atlas-inbox` capstone had this pattern four times: `SentimentResult`, `AttachmentClassification`, `InvoiceData`, `ReconciliationResult`. Each was the same three steps. Forty lines of boilerplate that appeared once every 80 lines of application code — a repeating pattern with no single place to fix when the fence-stripping logic had a bug.

A repeating pattern with no single place to fix is a missing primitive. CafeAI added it.

---

## The Pattern Before

```java
// The 40-line pattern, repeated four times in atlas-inbox
String prompt = buildSentimentPrompt(emailBody);
String raw    = app.prompt(prompt).call().text();

// Strip markdown fences — model often wraps JSON in ```json ... ```
String clean  = raw
    .replaceAll("(?s)```json\\s*", "")
    .replaceAll("(?s)```\\s*", "")
    .trim();

// Extract JSON block if model added prose before or after
int start = clean.indexOf('{');
int end   = clean.lastIndexOf('}');
if (start >= 0 && end > start) {
    clean = clean.substring(start, end + 1);
}

// Parse to target type
SentimentResult result = MAPPER.readValue(clean, SentimentResult.class);
```

Every developer who builds an AI application writes some version of this code. The fence formats vary (` ``` ` vs ` ```json ` vs ` ```JSON `). The prose locations vary (before, after, before and after). The error handling varies. The test coverage of edge cases varies.

---

## The Pattern After

```java
SentimentResult result = app.prompt(buildSentimentPrompt(emailBody))
    .returning(SentimentResult.class)
    .call(SentimentResult.class);
```

One line. The fence stripping, the JSON extraction, the parsing, the error wrapping — all handled by the framework.

---

## How It Works

Two classes handle structured output: `SchemaHintBuilder` and `ResponseDeserializer`.

### `SchemaHintBuilder`

`SchemaHintBuilder.build(Class<T>)` reflects on the target type and constructs a compact JSON example that demonstrates the expected schema:

```java
record SentimentResult(
    String tone,       // PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT
    String urgency,    // HIGH|MEDIUM|LOW
    boolean escalate,
    String recommendedAction
) {}

// SchemaHintBuilder.build(SentimentResult.class) produces:
// {
//   "tone": "PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT",
//   "urgency": "HIGH|MEDIUM|LOW",
//   "escalate": false,
//   "recommendedAction": "<string>"
// }
```

The hint is appended to the prompt with an instruction:

```
Respond with ONLY a valid JSON object matching this schema exactly.
No explanation, no preamble, no markdown formatting:
{
  "tone": "PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT",
  ...
}
```

The pipe-separated enum values (`"PROFESSIONAL|NEUTRAL|..."`) are generated automatically from Java enums and strings with enum-like naming. The developer defines the schema once in the record declaration; the builder generates the hint from reflection.

### `ResponseDeserializer`

`ResponseDeserializer.deserialise(String, Class<T>)` handles every format variant the model produces:

```java
// Clean JSON — passes through
"{\"tone\": \"FRUSTRATED\", \"urgency\": \"HIGH\", ...}"

// JSON with markdown fences — fences stripped
"```json\n{\"tone\": \"FRUSTRATED\", ...}\n```"

// Plain fence — stripped
"```\n{\"tone\": \"FRUSTRATED\", ...}\n```"

// Prose before/after — JSON block extracted
"Based on the email, here is my analysis:\n{\"tone\": \"FRUSTRATED\", ...}\nI hope this helps."

// All of the above: same code path, same result
```

If the cleaned text cannot be parsed as valid JSON for the target type, `ResponseDeserializer.StructuredOutputException` is thrown with a message that includes the target type name and the cleaned text — enough information to diagnose whether the problem is in the prompt (wrong schema hint) or the model (ignoring the format instruction).

---

## Works Identically Across All Three Modalities

The structured output pattern is not specific to text prompts. It applies to vision and audio calls with identical syntax:

```java
// Text prompt → typed result
SentimentResult r = app.prompt(prompt)
    .returning(SentimentResult.class)
    .call(SentimentResult.class);

// Vision call → typed result
AttachmentClassification r = app.vision(prompt, pdfBytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);

// Audio call → typed result
CallSummary r = app.audio(prompt, wavBytes, "audio/wav")
    .returning(CallSummary.class)
    .call(CallSummary.class);
```

The same `SchemaHintBuilder` and `ResponseDeserializer` handle all three paths. The modality is transparent to the structured output mechanism.

---

## Java Records as Schema Definitions

Java records are the natural schema definition mechanism for structured output. They are immutable, concise, and self-documenting. A record declaration is simultaneously the schema definition, the deserialization target, and the type used throughout the application:

```java
record InvoiceData(
    String vendorName,
    String invoiceNumber,
    String invoiceDate,
    String dueDate,
    String totalAmount,
    String currency,
    String poNumber,
    List<LineItem> lineItems,
    String paymentTerms,
    String extractionSource  // "PDF", "IMAGE", "EMAIL_BODY"
) {
    boolean isComplete() {
        return vendorName != null && invoiceNumber != null
            && totalAmount != null && !totalAmount.isBlank();
    }
}
```

The record's canonical constructor serves as validation. `isComplete()` is a domain method that belongs on the data type, not in the calling code. The type carries its schema, its validation, and its domain logic in one place.

`@JsonIgnoreProperties(ignoreUnknown = true)` on the record tolerates extra fields the model includes — common when the model adds a confidence score or explanation that wasn't in the schema.

---

## Nested Types

`SchemaHintBuilder` handles nested types. The `InvoiceData` record contains `List<LineItem>`:

```java
record LineItem(
    String description,
    String quantity,
    String unitPrice,
    String amount
) {}
```

The generated schema hint includes the nested structure:

```json
{
  "vendorName": "<string>",
  "invoiceNumber": "<string>",
  "lineItems": [
    {
      "description": "<string>",
      "quantity": "<string>",
      "unitPrice": "<string>",
      "amount": "<string>"
    }
  ],
  ...
}
```

The model correctly generates nested JSON. `ResponseDeserializer` maps it to the Java type hierarchy. No custom deserialiser needed.

---

## When to Use `.returning(Class)` vs Plain Text

Not every call needs structured output. The pattern is appropriate when:

- The response will be programmatically processed (routing, comparison, persistence)
- The response must be deserialised to a typed object
- Multiple fields need to be extracted from a single call

Plain `.call()` is appropriate when:

- The response is directly displayed to a user
- The response is a free-form explanation or draft text
- The exact format does not matter to downstream code

`atlas-inbox` uses structured output for classification, extraction, and reconciliation — all of which produce data that drives downstream logic. It uses plain text for response composition — the drafted vendor email is displayed as-is, with no programmatic processing.

---

## Testing Structured Output

Structured output is testable without real LLM calls. `ResponseDeserializer` is a pure function:

```java
@Test
void stripsJsonFence() {
    String raw = "```json\n{\"x\":\"1\",\"y\":\"2\"}\n```";
    assertThat(ResponseDeserializer.strip(raw))
        .isEqualTo("{\"x\":\"1\",\"y\":\"2\"}");
}

@Test
void deserialisesFromFencedJson() {
    Point p = ResponseDeserializer.deserialise(
        "```json\n{\"x\":\"3\",\"y\":\"4\"}\n```", Point.class);
    assertThat(p.x()).isEqualTo("3");
    assertThat(p.y()).isEqualTo("4");
}

@Test
void throwsOnUnparseable() {
    assertThatThrownBy(() ->
        ResponseDeserializer.deserialise("not json at all", Point.class))
        .isInstanceOf(ResponseDeserializer.StructuredOutputException.class)
        .hasMessageContaining("Point");
}
```

`SchemaHintBuilder` is also a pure function:

```java
@Test
void enumFieldGeneratesPipeSeparatedValues() {
    String hint = SchemaHintBuilder.build(WithEnum.class);
    assertThat(hint).contains("APPROVED|QUERIED|REJECTED");
}
```

These tests run in milliseconds — no API calls, no infrastructure. The structured output mechanism is fully testable in isolation, which is what it means for a concern to be properly separated.

---

## Post 11 — Production-Grade AI

Post 11 covers the operational side: token budgets, retry policies, observability, and what it took to make `atlas-inbox` production-ready. The `Thread.sleep` calls that existed in the first version — application code managing the framework's concern — are the starting point.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
