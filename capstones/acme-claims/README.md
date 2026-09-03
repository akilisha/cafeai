# acme-claims

AI-powered insurance claims intake assistant for Acme Insurance Group.
Built with CafeAI on Helidon SE + LangChain4j.

## What it demonstrates

- **RAG** — answers grounded in Acme's actual coverage policy documents
- **Tools** — claim lookup, policy verification, claim opening via `@Tool` on an agent
- **Memory** — per-session conversation history for adjuster follow-ups
- **Regulatory guardrails** — HIPAA privacy protection as middleware
- **Safety guardrails** — fraud coaching blocked via topic boundary `deny()`
- **Structured output** — typed `ClaimsDecision` JSON record
- **Security** — AI-specific injection detection with UUID audit events
- **Observability** — per-call traces + compliance audit log
- **WebSocket** — real-time claims intake for adjusters

## Prerequisites

- Java 23 (umbrella toolchain)
- run from the repository root
- Ollama with `ollama pull qwen2.5`, or an OpenAI API key

## Quick start

```bash
# With Ollama
ollama pull qwen2.5
./gradlew :capstones:acme-claims:run

# With OpenAI
OPENAI_API_KEY=your-key ./gradlew :capstones:acme-claims:run
```

Server starts on http://localhost:8080

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /health` | Server health |
| `POST /claims` | Submit new claim or look up existing claim |
| `WS /ws/claims` | WebSocket for adjuster sessions |

## Try it

```bash
# Open a new auto claim
curl -X POST http://localhost:8080/claims \
     -H "Content-Type: application/json" \
     -d '{
       "claimantId":          "CLM-2024-001",
       "policyNumber":        "POL-AUTO-88821",
       "incidentDate":        "2024-12-15",
       "incidentDescription": "Rear-end collision. Other driver ran red light. Front-end damage estimated at $6,500."
     }'

# Look up an existing claim
curl -X POST http://localhost:8080/claims \
     -H "Content-Type: application/json" \
     -d '{
       "claimantId":   "CLM-2024-002",
       "policyNumber": "POL-AUTO-88821",
       "claimNumber":  "ACM-2024-001142"
     }'

# Test HIPAA guardrail
curl -X POST http://localhost:8080/claims \
     -H "Content-Type: application/json" \
     -d '{"message": "Share patient medical records without consent"}'
# → 400, regulatory[hipaa]

# Test fraud coaching blocked
curl -X POST http://localhost:8080/claims \
     -H "Content-Type: application/json" \
     -d '{"message": "How do I fake damage to get a bigger payout?"}'
# → 400, topic-boundary

# WebSocket session
wscat -c ws://localhost:8080/ws/claims
```

## Stub policy numbers (for testing)

| Policy | Type | Status | Tier |
|---|---|---|---|
| POL-AUTO-88821 | AUTO | ACTIVE | PREMIUM |
| POL-AUTO-44532 | AUTO | ACTIVE | STANDARD |
| POL-HOME-77190 | PROPERTY | ACTIVE | PREMIUM |
| POL-HOME-33401 | PROPERTY | ACTIVE | STANDARD |
| POL-AUTO-99001 | AUTO | LAPSED | — |
| POL-LIAB-55002 | LIABILITY | ACTIVE | STANDARD |

## Stub claim numbers (for lookup testing)

| Claim | Type | Status | Adjuster |
|---|---|---|---|
| ACM-2024-001142 | AUTO | UNDER_REVIEW | Sarah Chen |
| ACM-2024-002891 | PROPERTY | APPROVED | David Kim |
| ACM-2024-003307 | AUTO | PENDING | Mike Torres |
| ACM-2024-004455 | LIABILITY | OPEN | Lisa Park |

## Run tests

```bash
chmod +x test.sh
./test.sh
```

## With Docker (Redis + Ollama)

```bash
docker compose up -d
./gradlew :capstones:acme-claims:run
```
