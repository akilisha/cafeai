# atlas-inbox — Demo Walkthrough

**For the CafeAI evangelist. What to say and show at each step.**

---

## Opening (30 seconds)

> "This is atlas-inbox — Meridian Home Loans' vendor invoice processor.
> It reads unread vendor emails from Gmail, reads the attachments visually,
> reconciles the amounts against what was contracted, and sends the reply.
> It runs as a batch job every morning. An AP clerk used to spend 3-4 hours
> on this. Let me show you what it does."

Show the inbox. Point out the FedEx invoice email with the PDF attachment.

---

## Step 1 — Start the dry run

```bash
./gradlew run -Pdry
```

> "Dry run first — same processing, no emails sent. We always validate
> before going live."

Point to the startup output:

```
CafeAI module loaded: cafeai-tools v0.1.0
CafeAI module loaded: cafeai-guardrails v0.1.0
AI provider registered: openai (gpt-4o)
GuardRail registered: jailbreak (PRE_LLM)
Registered 2 tool(s) from VendorContractLookup
Registered 1 tool(s) from DiscrepancyRecorder
Registered 1 tool(s) from InvoiceApprover
```

> "CafeAI discovers modules via ServiceLoader — tools, guardrails, all of it.
> The developer registers tools with `app.tool()`. The LLM decides when
> to call them. We never write the call sequence."

---

## Step 2 — Pre-filter (free)

```
Email 3 of 5  [id: ...]
  From:    Anthropic <no-reply@mail.anthropic.com>
  SKIPPED -- not a vendor email (pre-filter)
```

> "The pre-filter runs before any AI call. Gmail read, subject check,
> done. Zero tokens burned on a login email or a webinar invite.
> In a real AP inbox this eliminates 60-70% of emails before the AI
> sees them."

---

## Step 3 — Sentiment analysis

```
Sentiment: NEUTRAL / MEDIUM / escalate=false
```

> "Every email gets a sentiment pass first. Tone, urgency, and an
> escalation flag. If a vendor is threatening legal action or service
> suspension, we don't process it normally — we escalate immediately.
> The sentiment result drives the routing. The LLM output is a
> control flow signal, not just a log entry."

Show the three test emails from `SentimentAnalysisTest`:
- NEUTRAL / LOW — polite follow-up
- FRUSTRATED / HIGH — third attempt, affecting cash flow
- HOSTILE / CRITICAL / escalate=true — final notice, legal action

---

## Step 4 — Attachment classification (multimodal)

```
Classifying: invoice - Fed Ex 8-995-79991 $2,015.95.pdf
Classification: INVOICE (isInvoice=true, confidence=HIGH)
```

> "The PDF goes directly to gpt-4o as a visual input — not text extraction,
> not OCR preprocessing. The model reads it the same way a human would.
> It classifies: is this an invoice, a packing list, a shipping receipt?
> Only invoices proceed. Everything else is logged and skipped."

Open the FedEx PDF. It's a 15-page document with shipment details,
surcharges, tracking IDs, three payment categories. Point out the complexity.

> "15 pages. The model reads the summary page, identifies it as an invoice,
> HIGH confidence. One call."

---

## Step 5 — Invoice extraction

```
Invoice: FedEx | 8-995-79991 | $2015.95
```

> "Same model, second call. Extract the structured fields — vendor name,
> invoice number, total amount, PO number. From a 15-page FedEx Ground
> invoice with three billing categories. The extraction prompt tells it
> exactly what schema to return. Jackson parses it into a Java record."

Show `InvoiceData.java` — a clean record with `isComplete()`.

> "If the extraction is incomplete — missing vendor, invoice number, or amount —
> the email is skipped. We don't guess."

---

## Step 6 — Tool-calling (the key moment)

```
Invoking tool 'lookupVendorByName' [INTERNAL] with args: {"arg0":"FedEx"}
Invoking tool 'getContractedAmount' [INTERNAL] with args: {"arg0":"VND-1001","arg1":"MONTHLY"}
Invoking tool 'approveInvoice' [INTERNAL] with args: {"arg0":"VND-1001","arg1":"8-995-79991","arg2":"2015.95","arg3":"MONTHLY"}
[APPROVED] vendor=VND-1001 | invoice=8-995-79991 | amount=2015.95
```

> "This is the part I want you to pay attention to.

> We told the LLM: here is an invoice, here are some tools, figure it out.
> We did NOT write: call lookupVendor, then call getContractedAmount, then
> decide, then call approveInvoice.

> The LLM reasoned its way through that sequence. It called three tools
> in the right order, passed the right arguments between them, computed
> $2,015.95 vs $1,800 contracted with 15% tolerance — that's $2,070 max —
> so $2,015.95 is within tolerance, approve.

> That reasoning loop is LangChain4j. CafeAI exposes it via `app.tool()`.
> The developer writes the tool methods. The LLM writes the logic."

Show `VendorContractLookup.java`. Point out the `@CafeAITool` annotations.

> "Each annotation is a description the LLM reads when deciding which tool
> to call. The description IS the API contract between the developer and
> the model."

---

## Step 7 — Response composition

```
[DRY RUN] Reply drafted (not sent):
Dear FedEx Team,

Thank you for submitting invoice number 8-995-79991. We have received
the invoice, and I am pleased to inform you that it has been approved
for processing, as the amount falls within the contracted tolerance limit.
Payment will be processed according to the terms outlined in our contract...
```

> "The reply is drafted by the LLM given the reconciliation result.
> Three templates in the system: approval, query, discrepancy.
> The tone and content match the decision. A discrepancy reply is formal
> and asks the vendor not to resubmit. An approval reply is warm and
> confirms processing."

---

## Step 8 — Summary

```
Emails processed: 5
Approvals:        1
Discrepancies:    0
Escalations:      0
Skipped:          3
Errors:           0
```

> "One approval, three pre-filtered, zero errors. In a real run —
> `./gradlew run` without the dry flag — the FedEx reply goes out.

> This ran in under 2 minutes including the rate-limit pauses.
> On a paid OpenAI tier, remove the pauses and it runs in 20 seconds."

---

## The boundary question (for technical audiences)

> "What does CafeAI own and what doesn't it own?

> CafeAI owns: the AI calls, the tool registry, the guardrail middleware,
> the prompt pipeline. That's `app.ai()`, `app.tool()`, `app.guard()`,
> `app.prompt()`.

> CafeAI does not own: Gmail, OAuth2, the billing API stubs, the batch
> orchestration loop. Those are plain Java. The four Gmail classes are
> each 30-50 lines. The orchestrator is a for-loop.

> The point is that CafeAI is an opinion on top of Helidon and LangChain4j —
> not a cage around them. If you need something CafeAI doesn't abstract,
> you write it in Java. The escape hatch is always there."

Show `app.helidon()` in the SPEC.md if asked.

---

## Closing

> "atlas-inbox is Capstone 4 of the CafeAI tutorial series. It covers:
> CafeAI as a library with no HTTP server, multimodal vision prompting,
> tool-calling with side effects, sentiment-driven routing, and the
> boundary between what the AI owns and what Java owns.

> The full series goes from a hello-world HTTP server to this — a
> production-grade AI-powered AP processor — in four capstones."

---

## Common questions

**Q: Why gpt-4o and not gpt-4o-mini?**
Vision on scanned documents with handwriting. Mini works for clean digital
PDFs but struggles with the Heiden and Ultratech scanned invoices. 4o reads
the AP stamps and handwritten amounts correctly.

**Q: What happens when a vendor isn't in the system?**
`lookupVendorByName` returns a not-found response. The LLM falls back to
QUERIED — asks the vendor for clarification. Nothing crashes.

**Q: What about the rate limit pauses?**
Free tier constraint — 30,000 tokens per minute. Processing one email with
a PDF attachment costs 8,000-15,000 tokens. Pauses keep us under the limit.
On a paid tier this is not a concern. The pause lines are documented for removal.

**Q: Is the Gmail credential secure?**
The OAuth2 JSON is gitignored. The token is stored locally in `tokens/`.
In production, use a secrets manager and service account credentials.

**Q: Can this handle multiple attachments?**
Yes — the classifier runs on each attachment. The first one classified as
an invoice proceeds to extraction. Additional invoice attachments are logged
but skipped (one invoice per email is the assumption). Multi-invoice emails
are a Phase 12 extension.
