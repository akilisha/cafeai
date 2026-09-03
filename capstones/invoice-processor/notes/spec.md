# Capstone 4: `atlas-inbox` — Meridian Home Loans Vendor Invoice Processor

**Status:** 📋 Spec — not started  
**Extends:** Capstone 2 (`meridian-qualify`) — same lender, new problem domain  
**Owner:** Meridian Accounts Payable team  
**CafeAI version:** 0.1.0-SNAPSHOT (Helidon 4.4.0 + LangChain4j 1.11.0)

---

## The Business Problem

Meridian Home Loans receives vendor invoices by email from appraisal companies, title agencies,
property inspection firms, and legal service providers — the vendors that make mortgage
processing possible. The AP team manually:

1. Opens each email and decides if it contains an invoice
2. Downloads attachments and reads them to find invoice details
3. Cross-checks the amount against what was contracted
4. Replies to the vendor with approval, a question, or a payment schedule
5. Escalates disagreements to an AP supervisor

This process takes 3–4 hours daily and introduces errors. Vendors with frustrated, urgent tones
get treated the same as routine follow-ups. Discrepancies that should be caught in hours
sometimes surface in weeks.

`atlas-inbox` automates this with CafeAI as the intelligence layer.

---

## What the System Does

```
Gmail (unread vendor emails)
    │
    ▼
Sentiment Analysis          ← tone + urgency → escalation decision
    │
    ▼
Attachment Classification   ← is this an invoice? image/PDF recognition
    │
    ▼
Invoice Extraction          ← structured fields from attachment or body
    │
    ▼
Vendor Lookup               ← @CafeAITool → internal billing API
    │
    ▼
Reconciliation              ← expected vs. actual amounts
    │
    ▼
Response Composition        ← approval / query / escalation email
    │
    ▼
Gmail (send reply)
```

CafeAI owns the intelligence column. Gmail and the billing API are plain Java.

---

## Architecture Decisions

### 1. Batch job, not HTTP server

This is not a web application. There is no `app.listen(8080)`.
`atlas-inbox` is a command-line batch processor:

```
./gradlew run               → processes today's unread vendor emails
./gradlew run --args=dry    → dry run, no emails sent, results printed only
```

CafeAI works perfectly well as a library without HTTP. The developer calls
`app.prompt()`, `app.tool()`, and `app.observe()` directly from a `main()` method.
This is an important teaching point — CafeAI is not Spring Boot. It does not require
a running server.

### 2. One class, one responsibility

Every AI capability is isolated in its own class, annotated with `@CafeAITool`
where appropriate. No god classes. Names are semantically descriptive:

```
GmailUnreadEmailFetcher          fetches unread emails from Gmail
GmailEmailBodyReader             reads the plain text body of an email
GmailAttachmentDownloader        downloads a specific attachment as bytes
GmailEmailSender                 sends a reply or new email
EmailSentimentAnalyzer           analyzes tone and urgency
AttachmentTypeClassifier         classifies whether attachment is an invoice
InvoiceDataExtractor             extracts structured fields from invoice content
VendorContractLookup             looks up what a vendor is contracted to bill
InvoiceAmountReconciler          compares actual vs expected amounts
DiscrepancyRecorder              records a discrepancy in the internal system
InvoiceApprover                  marks an invoice approved in the system
ResponseComposer                 drafts the reply email text
EscalationNotifier               notifies AP supervisor of urgent cases
```

### 3. Multimodal — LLM reads images and PDFs directly

Attachment classification and invoice extraction use `ImageContent` and
`PdfFileContent` from LangChain4j — passed directly to `gpt-4o` which can read
them visually. This is NOT done through `@CafeAITool` (which only handles plain
Java types). It is done through direct `app.prompt()` calls with multimodal content.

### 4. Sentiment drives routing, not just logging

The `EmailSentimentAnalyzer` returns a structured result with:
- `tone`: POSITIVE | NEUTRAL | FRUSTRATED | HOSTILE
- `urgency`: LOW | MEDIUM | HIGH | CRITICAL
- `escalate`: boolean
- `keyPhrases`: list of phrases that drove the decision

If `escalate=true`, the email bypasses the normal queue and an escalation notification
fires immediately via `EscalationNotifier`.

### 5. Real Gmail API, real OAuth2

No mocks. The capstone walks through Google Cloud project setup, OAuth2 credentials,
and the Gmail Java client. This is intentional — a real credential flow teaches the
pattern once, and it is directly reusable.

---

## Tools Inventory

### Gmail Tools (plain Java, no @CafeAITool — these are plumbing)

These classes interact with Gmail but are NOT exposed to the LLM. They are called
by the orchestration code directly.

| Class | Responsibility |
|---|---|
| `GmailUnreadEmailFetcher` | Lists unread emails matching a label filter |
| `GmailEmailBodyReader` | Returns plain text body of a specific email |
| `GmailAttachmentDownloader` | Downloads attachment bytes by emailId + filename |
| `GmailEmailSender` | Sends a reply or new email |

### AI Tools (@CafeAITool — exposed to the LLM for tool-calling)

These are called by the LLM during its reasoning loop when it decides it needs them.

| Class | Method | Description |
|---|---|---|
| `VendorContractLookup` | `lookupVendor(vendorName)` | Returns contract details for a vendor |
| `VendorContractLookup` | `getExpectedAmount(vendorId, period)` | Returns expected billing amount |
| `DiscrepancyRecorder` | `recordDiscrepancy(vendorId, invoiceId, reason)` | Logs a discrepancy |
| `InvoiceApprover` | `approveInvoice(invoiceId, vendorId, amount)` | Marks invoice approved |

### AI Prompt Calls (direct app.prompt() — orchestration layer)

These are not tools — they are explicit LLM calls made by the orchestration code,
each with a specific multimodal or structured extraction purpose.

| Purpose | Input | Output |
|---|---|---|
| Sentiment analysis | Email body text | JSON: tone, urgency, escalate, keyPhrases |
| Attachment classification | Image or PDF content | JSON: isInvoice, confidence, docType |
| Invoice extraction from image | ImageContent + extraction prompt | JSON: vendor, invoiceNo, amount, date, lineItems |
| Invoice extraction from PDF | PdfFileContent + extraction prompt | JSON: same as above |
| Invoice extraction from body | Email body text | JSON: same as above |
| Response composition | Reconciliation result + vendor details | Plain text email body |
| Escalation composition | Sentiment result + email context | Plain text escalation note |

---

## Twelve Phases

---

### Phase 1 — Project Scaffold
Set up `atlas-inbox` as a standalone Gradle project depending on `cafeai-core`,
`cafeai-tools`, and `cafeai-guardrails` from the local Maven repository.
Confirm it compiles clean.

**What you learn:** CafeAI as a library dependency, not a parent project.
Gradle publishToMavenLocal → consuming project pattern.

---

### Phase 2 — Gmail OAuth2 Setup
Configure Google Cloud project, enable Gmail API, create OAuth2 credentials,
implement the Gmail client wrapper using Google's Java client library.
Write `GmailConnectionTest` — connect, list labels, confirm authentication works.

**What you learn:** OAuth2 credential flow, Google API client setup, the clean
separation between AI plumbing and business plumbing.

---

### Phase 3 — Gmail Tool Classes
Implement the four Gmail tool classes: `GmailUnreadEmailFetcher`,
`GmailEmailBodyReader`, `GmailAttachmentDownloader`, `GmailEmailSender`.
Each class is independently testable. Write a manual integration test
that fetches one real email and prints its body.

**What you learn:** One class, one responsibility. Each class does exactly one
thing and is testable in isolation before the AI layer touches it.

---

### Phase 4 — OpenAI Provider + System Prompt
Wire up `app.ai(OpenAI.gpt4o())` using the `OPENAI_API_KEY` environment variable.
Write the AP system prompt — Meridian's identity, professional tone, what the
assistant is and is not authorised to decide.
Confirm the model responds in the correct persona with a simple test call.

**What you learn:** OpenAI gpt-4o is required here (not gpt-4o-mini) because
multimodal attachment processing needs the vision capability.

---

### Phase 5 — Sentiment Analysis
Implement `EmailSentimentAnalyzer`. Use `app.prompt()` with a structured
extraction prompt that forces JSON output. Parse the JSON result into a
`SentimentResult` record: `tone`, `urgency`, `escalate`, `keyPhrases`.
Test with three real email samples: a polite follow-up, a frustrated chaser,
and a hostile payment threat.

**What you learn:** Structured extraction via prompt engineering. No schema
framework needed — a clear JSON instruction in the prompt + Jackson parsing
is sufficient.

---

### Phase 6 — Attachment Classification (Multimodal)
Implement `AttachmentTypeClassifier`. This is the first multimodal call —
download an attachment as bytes, convert to `ImageContent` (for images) or
`PdfFileContent` (for PDFs), pass to `app.prompt()` alongside a classification
prompt. The LLM returns JSON: `isInvoice`, `confidence`, `docType`.
Test with a real invoice image, a real non-invoice attachment (e.g., a contract),
and a handwritten note photo.

**What you learn:** `ImageContent.from(base64, mimeType)` and
`PdfFileContent.from(bytes)` — multimodal content in a plain `app.prompt()` call.
This is not an `@CafeAITool`. It is a direct prompt call with non-text input.

---

### Phase 7 — Invoice Data Extraction (Multimodal)
Implement `InvoiceDataExtractor`. Three extraction paths, all returning the
same `InvoiceData` record:
- From image (scanned paper invoice or photo)
- From PDF (digital invoice)
- From email body text (vendor pasted details inline)

The extraction prompt instructs the LLM to return structured JSON with:
`vendorName`, `invoiceNumber`, `invoiceDate`, `dueDate`, `totalAmount`,
`lineItems[]`, `currency`.

Test each path with real samples.

**What you learn:** The same extraction task, three input formats, one output
schema. The LLM handles the format variance — the Java code handles the routing.

---

### Phase 8 — Billing API Tools (@CafeAITool)
Implement `VendorContractLookup`, `DiscrepancyRecorder`, and `InvoiceApprover`
as `@CafeAITool`-annotated classes backed by stub HTTP calls (simulating Meridian's
internal billing API). Register them with `app.tool()`.
Test the tool-calling loop: give the LLM an extracted invoice and ask it
to look up the vendor and compare amounts. Watch it call the tools.

**What you learn:** `@CafeAITool` for side-effecting operations. The LLM decides
when to call these tools — the developer doesn't orchestrate the call sequence.

---

### Phase 9 — Reconciliation + Response Composition
Implement `InvoiceAmountReconciler` and `ResponseComposer`.
The reconciler compares `InvoiceData.totalAmount` against `VendorContractLookup`
expected amount and produces a `ReconciliationResult`: APPROVED | QUERIED |
DISCREPANCY_LOGGED.
The composer uses `app.prompt()` to draft the reply email body, given the
reconciliation result and vendor details.
Test end-to-end: email → extract → lookup → reconcile → compose reply.

**What you learn:** The full AI pipeline without Gmail sending. This is the
integration test before the real email goes out.

---

### Phase 10 — Escalation Path
Implement `EscalationNotifier`. When sentiment analysis returns `escalate=true`,
bypass the normal reconciliation queue and instead:
1. Compose an escalation note (via `app.prompt()`)
2. Send it to the AP supervisor (via `GmailEmailSender`)
3. Send an immediate acknowledgement to the vendor

Add guardrails: `GuardRail.jailbreak()` on all LLM calls.
Test with a hostile email sample — confirm it triggers escalation and not
the normal reconciliation path.

**What you learn:** Routing based on AI output. The sentiment result drives
control flow, not just logging.

---

### Phase 11 — Full Batch Orchestration
Wire everything together in `AtlasInboxProcessor.main()`:
1. Fetch unread emails
2. For each email: sentiment → classify attachments → extract → reconcile → respond
3. Escalation path for urgent/hostile
4. Summary report at the end of the run
5. Mark emails as processed (Gmail label)

Test with a real batch of 3–5 vendor emails covering the happy path,
a discrepancy, and an escalation.

**What you learn:** CafeAI as a library in a batch job. No HTTP server.
The AI capabilities compose naturally with plain Java orchestration.

---

### Phase 12 — Polish and Delivery
- `README.md` — setup, credentials, run instructions
- `dry-run` mode — processes emails but sends nothing, prints decisions
- `test.sh` — automated checks on the reconciliation logic
- `DEMO.md` — the evangelist walkthrough, what to say at each step

---

## Validation Checklist

```
□ Phase 1:  ./gradlew compileJava → BUILD SUCCESSFUL
□ Phase 2:  Gmail connection established, labels listed
□ Phase 3:  One real email fetched and body printed
□ Phase 4:  gpt-4o responds in Meridian AP persona
□ Phase 5:  Sentiment correctly classifies 3 email samples
□ Phase 6:  Classifier correctly identifies invoice vs. non-invoice attachment
□ Phase 7:  Invoice data extracted from image, PDF, and body text
□ Phase 8:  LLM calls @CafeAITool methods during reasoning loop
□ Phase 9:  Full pipeline: email → extract → lookup → reconcile → draft reply
□ Phase 10: Hostile email triggers escalation path, not reconciliation
□ Phase 11: Real batch run processes 3+ emails end-to-end
□ Phase 12: README + dry-run + DEMO.md complete
```

---

## What This Capstone Teaches

By the end of Phase 12, you can explain and demonstrate:

1. **CafeAI as a library** — no HTTP server required, composes with plain Java
2. **Multimodal prompting** — images and PDFs as LLM input, not just text
3. **One class, one responsibility** — the right granularity for AI tool design
4. **Sentiment-driven routing** — AI output as a control flow signal
5. **Structured extraction** — forcing typed JSON from unstructured input
6. **@CafeAITool for side effects** — the LLM decides when to call them
7. **Real OAuth2** — credential flow that is reusable in any Google API project
8. **The boundary** — what CafeAI owns (intelligence) vs. what Java owns (plumbing)

---

## Prerequisites for Starting

- Capstone 2 (`meridian-qualify`) completed — domain familiarity
- `./gradlew publishToMavenLocal` run in the CafeAI source tree
- Google Cloud project created
- Gmail API enabled
- OAuth2 credentials JSON file available
- `OPENAI_API_KEY` environment variable set (gpt-4o access required)
- A Gmail account with some test vendor emails (real or manually created)

---

*`atlas-inbox` — the intelligence layer for Meridian's accounts payable inbox.*
