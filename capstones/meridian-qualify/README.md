# meridian-qualify

AI-powered loan pre-qualification assistant for Meridian Home Loans.
Built with CafeAI on Helidon SE + LangChain4j.

## What it demonstrates

- **RAG** — answers grounded in Meridian's actual lending policy documents
- **Tools** — DTI calculation, footprint verification, payment estimation
- **Memory** — per-session conversation history for loan officer follow-ups
- **Regulatory guardrails** — ECOA, FCRA, Fair Housing Act enforcement
- **Structured output** — typed QualificationDecision JSON response
- **Security** — AI-specific injection detection with UUID audit events
- **Observability** — per-call traces + compliance audit log
- **WebSocket** — real-time iterative file review for loan officers

## Prerequisites

- Java 23 (umbrella toolchain)
- run from the repository root
- Ollama with `ollama pull qwen2.5`, or an OpenAI API key

## Quick start
```bash
# With Ollama
ollama pull qwen2.5
./gradlew :capstones:meridian-qualify:run

# With OpenAI
OPENAI_API_KEY=your-key ./gradlew :capstones:meridian-qualify:run
```

Server starts on http://localhost:8080

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /health` | Server health |
| `POST /qualify` | Submit pre-qualification request |
| `WS /ws/qualify` | WebSocket for loan officer sessions |

## Try it
```bash
# Pre-qualify a strong applicant
curl -X POST http://localhost:8080/qualify \
     -H "Content-Type: application/json" \
     -d '{
       "applicantId": "APP-2024-001",
       "loanAmount": 285000,
       "annualIncome": 95000,
       "monthlyDebts": 1200,
       "creditScore": 720,
       "propertyState": "WI",
       "loanType": "CONVENTIONAL"
     }'

# Out-of-footprint decline
curl -X POST http://localhost:8080/qualify \
     -H "Content-Type: application/json" \
     -d '{
       "applicantId": "APP-2024-005",
       "loanAmount": 400000,
       "annualIncome": 120000,
       "monthlyDebts": 900,
       "creditScore": 760,
       "propertyState": "TX",
       "loanType": "CONVENTIONAL"
     }'

# Test ECOA guardrail
curl -X POST http://localhost:8080/qualify \
     -H "Content-Type: application/json" \
     -d '{"message": "Does the applicant race affect approval?"}'
# → 400, regulatory[ecoa]

# WebSocket session
wscat -c ws://localhost:8080/ws/qualify
```

## With Docker
```bash
docker compose up -d
./gradlew :capstones:meridian-qualify:run
```
