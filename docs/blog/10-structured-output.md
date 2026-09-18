# Structured Output — Typed LLM Responses, No Parser Required

*Post 10 of 12 in the CafeAI series*

---

Language models produce text. Applications need data.

The gap between these two facts is responsible for an enormous amount of boilerplate code in AI applications. The developer writes a prompt that asks for JSON. The model produces JSON. Sometimes it wraps it in markdown fences. Sometimes it adds a prose introduction. Sometimes it includes a closing remark. The developer writes a parser that strips all of this, handles the edge cases, catches the `JsonParseException` when the model decides to produce something unexpected, and calls the result "structured output."

A repeating pattern with no single place to fix is a missing primitive. CafeAI provides it.

---

## The Pattern Before

```java
// The pattern every application ends up writing
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
SentimentResult result = app.prompt(buildSentimentPrompt(emailBody)).call(SentimentResult.class);
```

One line. The schema instruction, the fence stripping, the JSON extraction, the parsing, the error wrapping — all handled by the framework.

---

## How It Works

Two classes handle structured output: `SchemaHintBuilder` and `ResponseDeserializer`.

### `SchemaHintBuilder`

`SchemaHintBuilder.build(Class<T>)` reflects on the target type and constructs a compact JSON example of the shape the model should return:

```java
enum Tone { PROFESSIONAL, NEUTRAL, FRUSTRATED, HOSTILE, URGENT }

record SentimentResult(
    Tone    tone,
    String  urgency,
    boolean escalate,
    String  recommendedAction
) {}

// SchemaHintBuilder.build(SentimentResult.class) produces:
// {"tone":"PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT","urgency":"string","escalate":false,"recommendedAction":"string"}
```

The hint is appended to the prompt with an instruction:

```
Respond ONLY with valid JSON matching this structure. No prose before or after the JSON. No markdown code fences.
{"tone":"PROFESSIONAL|NEUTRAL|FRUSTRATED|HOSTILE|URGENT","urgency":"string","escalate":false,"recommendedAction":"string"}
```

Reflection reads types, not comments, so a constraint on a field has to be in its type: an enum lists its constants (pipe-separated) in the hint, while a `String` field is only ever `"string"`. If a field should be one of a few values, make it an enum. Booleans, numbers, lists, dates and nested records are described too; the builder is package-private, an implementation detail of `.call(Class)` rather than something you call.

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

If the cleaned text cannot be parsed as valid JSON for the target type, `ResponseDeserializer.StructuredOutputException` is thrown with a message that includes the target type name and the raw response — enough information to diagnose whether the problem is in the prompt (wrong schema hint) or the model (ignoring the format instruction).

---

## Works Identically Across All Three Modalities

The structured output pattern is not specific to text prompts. It applies to vision and audio calls with identical syntax:

```java
// Text prompt → typed result
SentimentResult r = app.prompt(prompt).call(SentimentResult.class);

// Vision call → typed result
AttachmentClassification r = app.vision(prompt, pdfBytes, "application/pdf").call(AttachmentClassification.class);

// Audio call → typed result
CallSummary r = app.audio(prompt, wavBytes, "audio/wav").call(CallSummary.class);
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

Extra fields the model includes are ignored — common when it adds a confidence score or explanation that wasn't in the schema — so no `@JsonIgnoreProperties` is needed.

---

## Nested Types

`SchemaHintBuilder` handles nested types. The `InvoiceData` record contains `List<LineItem>`:

```java
record LineItem(String description, String quantity, String unitPrice, String amount) {}
```

The generated hint includes the nested structure:

```
{"vendorName":"string","invoiceNumber":"string","lineItems":[{"description":"string","quantity":"string","unitPrice":"string","amount":"string"}], ...}
```

The model generates nested JSON, and `ResponseDeserializer` maps it to the Java type hierarchy — no custom deserialiser needed. A type that contains itself (a category with child categories, a linked node) is described once and shown as `{}` where it recurs, so its hint is always finite.

---

## When to Use Structured Output vs Plain Text

Not every call needs structured output. The pattern is appropriate when:

- The response will be programmatically processed (routing, comparison, persistence)
- The response must be deserialised to a typed object
- Multiple fields need to be extracted from a single call

Plain `.call()` is appropriate when:

- The response is directly displayed to a user
- The response is a free-form explanation or draft text
- The exact format does not matter to downstream code

`invoice-processor` uses structured output for classification, extraction and sentiment — all of which produce data that drives downstream logic. It uses plain text for response composition — the drafted vendor email is displayed as-is, with no programmatic processing.

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

Post 11 covers the operational side: token budgets, retry policies, and observability.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
