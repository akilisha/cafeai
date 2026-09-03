# invoice-processor

**Meridian Home Loans — Vendor Invoice Processor**

A CafeAI-powered batch job that reads unread vendor emails from Gmail,
classifies attachments, extracts invoice data with `app.vision()`, reconciles
amounts against contracted rates through a tool-calling agent (`app.agent()`),
and drafts professional replies — all driven by `gpt-4o`.

This is Capstone 4 of the CafeAI series (formerly `atlas-inbox`). It extends the
Meridian Home Loans domain from Capstone 2 (`meridian-qualify`) into accounts
payable automation. It lives in the CafeAI umbrella build and consumes the
framework via `project(':cafeai-*')` — no published-artifact step.

---

## What it does

```
Gmail (unread emails)
    │
    ▼
Pre-filter             ← skip non-vendor emails with no token cost
    │
    ▼
Sentiment Analysis     ← tone + urgency → escalation decision
    │
    ├─ escalate=true → supervisor alert + vendor acknowledgement
    │
    ▼
Attachment Classification  ← is this an invoice? (multimodal vision)
    │
    ▼
Invoice Extraction         ← structured fields from PDF, image, or body
    │
    ▼
Reconciliation             ← contracted vs invoiced, via a tool-calling agent
    │
    ▼
Response Composition       ← draft vendor reply
    │
    ▼
Gmail (send reply)         ← skipped in dry-run mode
```

---

## Prerequisites

- Java 23 (the umbrella toolchain)
- OpenAI API key with `gpt-4o` access
- Google Cloud project with Gmail API enabled
- OAuth2 credentials JSON file

---

## Setup

### 1. OpenAI API key

```bash
# macOS/Linux
export OPENAI_API_KEY=sk-...

# Windows PowerShell
$env:OPENAI_API_KEY="sk-..."
```

### 2. Gmail OAuth2 credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Create a project, enable Gmail API
3. Create OAuth2 credentials → Desktop app → Download JSON
4. Copy to: `capstones/invoice-processor/src/main/resources/credentials/gmail-credentials.json`

The first run opens a browser for authorization. After that, the token is
stored in `capstones/invoice-processor/tokens/` and subsequent runs are silent.
Both paths are gitignored.

### 3. Build

```bash
# from the repository root
./gradlew :capstones:invoice-processor:compileJava
```

---

## Running

All commands run from the repository root.

### Dry run (safe — no emails sent)

```bash
./gradlew :capstones:invoice-processor:run -Pdry
```

Processes emails, prints decisions and draft replies, sends nothing.
Use this to verify behaviour before going live.

### Live run

```bash
./gradlew run
```

Processes emails and sends replies. Escalations go to the supervisor
address configured in `InvoiceProcessor.SUPERVISOR_EMAIL`.

---

## Rate limits

The free tier of OpenAI (`gpt-4o`) allows 30,000 tokens per minute.
Processing one email with attachments consumes roughly 8,000–15,000 tokens
across classification, extraction, reconciliation, and composition.

`app.budget(TokenBudget.perMinute(30_000))` and `app.retry(RetryPolicy.onRateLimit()...)`
handle throttling — no `Thread.sleep()` in application code. On a paid tier,
raise the budget.

---

## Project structure

```
src/main/java/io/meridian/invoice/
├── InvoiceProcessor.java          entry point, orchestration
├── GmailClientFactory.java        OAuth2 connection
│
├── gmail/                         list / read / download / send (plain Java, no LLM)
├── sentiment/                     EmailSentimentAnalyzer + SentimentResult
│                                    app.prompt().returning(SentimentResult.class)
├── classification/                AttachmentTypeClassifier + AttachmentClassification
│                                    app.vision().returning(...)
├── extraction/                    InvoiceDataExtractor + InvoiceData
│                                    app.vision() / app.prompt() .returning(...)
├── billing/                       VendorContractLookup, DiscrepancyRecorder,
│                                    InvoiceApprover  — LangChain4j @Tool classes
├── reconciliation/                ReconciliationAgent (app.agent) + ReconciliationVerdict
│                                    InvoiceAmountReconciler + ReconciliationResult
├── response/                      ResponseComposer — drafts the vendor reply
└── escalation/                    EscalationNotifier

src/test/java/io/meridian/invoice/   live-service harnesses, run via Gradle tasks:
  :capstones:invoice-processor:GmailConnectionTest   verify Gmail auth
  :capstones:invoice-processor:SentimentTest         three sentiment scenarios
  :capstones:invoice-processor:ClassificationTest    classify the real PDFs
  :capstones:invoice-processor:ExtractionTest        extract from PDF + body
  :capstones:invoice-processor:BillingToolsTest      the reconciliation agent's tool loop
  :capstones:invoice-processor:PipelineTest          full pipeline, no send
  :capstones:invoice-processor:EscalationTest        hostile email + jailbreak
```

---

## Vendor roster (stub data)

| Vendor | ID | Type | Tolerance |
|---|---|---|---|
| FedEx | VND-1001 | Courier | ±15% |
| Heiden, Inc. | VND-1002 | Manufacturing | exact |
| Honeywell International | VND-1003 | IP Licensing | exact |
| KSO Metalfab | VND-1004 | Fabrication | exact |
| Liberty Fastener | VND-1005 | Hardware | ±5% |
| Sigmatron International | VND-1006 | Electronics | exact |
| Ultratech, Inc. | VND-1007 | Sheet Metal | ±3% |

In production, replace `VendorContractLookup` with real ERP API calls.
