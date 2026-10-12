# Arc 2 — CafeAI at Work (outline)

*A plan for the second arc of the blog series. Not a post.*

---

## Why a second arc

Arc 1 (posts 1–12) is the framework: one developer, building one app up the adoption ladder,
one capability at a time, ending with the capstones. It is complete, and it stays as it is.

What came after it doesn't fit on that ladder: signing in to a model with the company's
account, verified callers, model calls made as a person, per-caller data, other agents calling
the app. These aren't more rungs. They bring in other people: the company, its users, other
agents. They are also where CafeAI is aiming for its niche. The problems a Java developer
actually has at work matter more than another web server competing with the incumbents.

So arc 2 grows the series in a second dimension. Arc 1 asked *what can my app do with a
model?* Arc 2 asks *what changes when the app, and I, are at work?*

## The thread

**The protagonist** is a Java developer on a company laptop. They have:

- a corporate model account (company single sign-on, an admin's rules);
- no API key, because the company forbids them;
- colleagues who will use the app;
- a security team who will ask who did what.

**How each post works:**

- It opens with a concrete problem from that developer's week.
- It solves the problem with code the reader can run.
- It ends with what real accounts or real tests found. Arc 1's capstones set this habit:
  "an application running against real infrastructure finds what tests do not". Arc 2 keeps
  it, with the vendors themselves (quotas, a CLI that crashes, a name that prints as `Rah?s`).

**The order** moves outward from the developer's desk:

- the developer's own access;
- the developer's own loop;
- the people who use the app;
- acting as them;
- what each of them may see and spend;
- other agents;
- the whole thing in one application.

## The posts

Numbering continues from arc 1 (13 onwards), so links and the series table stay simple.

### 13 — No Key on a Work Laptop: `cafeai login`

- **The problem:** the company bans API keys, and `export OPENAI_API_KEY` from post 3 isn't an
  option. The developer can sign in to Claude, Azure, AWS or Google through the company's
  single sign-on, but their app can't use that sign-in.
- **What it shows:**
  - `cafeai login claude`, through Anthropic's `ant`, the Claude Console and the company's SSO;
  - `cafeai login azure | aws | google`, after which `Anthropic.onVertex(...)`, Foundry, Azure
    OpenAI and Bedrock providers pick up the sign-in with no code;
  - keys saved once for the vendors that only have keys;
  - `cafeai status`;
  - the lookup order (code, then environment variable, then the sign-in).
- **What testing found** (MILESTONE-20):
  - new projects get zero Claude quota on every cloud;
  - `az` 2.91 crashes with no subscription;
  - an AWS SSO profile with no region;
  - names printing as `Rah?s` on a Windows console.

  Each was found by a real account, and each changed the code.
- **Sources:** `docs/design/LOGIN.md`, MILESTONE-20, DEVELOPER_GUIDE "API keys".
- **Ends with:** two questions kept apart. *How does my app reach the model?* (this post) is not
  *who uses my app?* (post 15).

### 14 — The Inner Loop at Work: reload, record, replay, evals

- **The problem:** model calls cost money and need credentials, which CI doesn't have and
  colleagues shouldn't need to run the tests.
- **What it shows:**
  - `cafeai-dev`: save a file, and the app reloads in about half a second;
  - `cafeai-test`: record model calls once, replay them with no key and no cost;
  - evals as tests.
- **What testing found:** what reload can and can't handle (it says so every time it starts).
- **Sources:** DEVELOPER_GUIDE §27, §28, §30.

### 15 — Who's Asking? Verified callers

- **The problem:** the app goes to colleagues, and "anyone with the URL" isn't acceptable.
- **What it shows:**
  - `Auth.bearer` over any OpenID Connect issuer;
  - `Auth.require` (groups, scopes);
  - browser sign-in (`Auth.login`, tokens kept server-side);
  - terminal sign-in (device and loopback);
  - `FakeIssuer` for developing without an identity provider.
- **The turn:** the caller goes everywhere. Conversations belong to whoever started them, and
  usage and audit records name the caller.
- **Sources:** DEVELOPER_GUIDE §32.1–32.4, 32.6, 32.10; `docs/design/IDENTITY.md`.

### 16 — On Their Behalf: model calls as the person

- **The problem:** the security team asks why every model call looks like the app, and not like
  the person who asked.
- **What it shows:**
  - token exchange (RFC 8693) and Entra's on-behalf-of;
  - per-person cloud credentials on a server: Bedrock (`AwsCredentials.assumeRoleAsCaller`),
    Vertex AI (Google workforce federation), the Claude API (workload identity federation).
- **The contrast with post 13:** there it was the developer's own sign-in on their laptop;
  here it's each user's identity on a server. Same models, different question.
- **Sources:** DEVELOPER_GUIDE §32.5; IDENTITY.md (the outbound table).

### 17 — What Each Caller May See and Spend

- **The problem:**
  - RAG over company documents must not show a person what they couldn't open themselves;
  - finance wants to know what each route and each person costs.
- **What it shows:**
  - RAG under PostgreSQL row-level security: the database decides, and CafeAI holds no
    permissions;
  - usage and cost per route and per caller.
- **Sources:** DEVELOPER_GUIDE §32.7, §31.

### 18 — Tools for Other Agents: MCP

- **The problem:** other teams' agents want to call the app.
- **What it shows:**
  - chosen routes and `@Tool` objects served over MCP;
  - `Auth.mcp`, with resource metadata so agents find the issuer;
  - WebSockets, protected the same way.
- **Sources:** DEVELOPER_GUIDE §29, §32.8, §32.9.

### 19 — The Capstone: `acme-desk`

- **What it is:** a team knowledge desk where what you can read decides what the AI tells you:
  - browser sign-in;
  - a WebSocket that runs as the person;
  - RAG under row-level security;
  - no API key anywhere.
- **The parallel with post 12:** the application that pulls arc 2 together, and what building
  it found.
- **Sources:** `capstones/acme-desk`.

## Format

- **Version line.** Each post starts with its arc, its place and the version it was written
  against: *Arc 2 — CafeAI at Work, post 13 · written against CafeAI 0.6.0*. Drift is then
  visible, not silent. Arc 1's posts get the same line (0.2.0 to 0.5.x, from git history).
- **Series table.** The table at the end of each post is grouped by arc.
- **README.** The README's "Blog Series" section lists the two arcs with a sentence each.
- **Touch-ups to arc 1.** Besides the version line, post 3 gets a short note next to
  `export OPENAI_API_KEY`: on a work machine, `cafeai login`, see post 13. Nothing else in arc 1
  changes.
- **Length and style.** About 1,200–2,000 words per post, like arc 1. Code the reader can run,
  anchored to the repository; no feature tours.

## Open questions

- **Whether post 14 belongs in arc 2.** It could stand on its own as an interlude. It fits the
  "at work" thread (no keys in CI), so it's placed here.
- **Whether 17 should split** into a RAG post and a cost post. Together they answer the one
  question a manager asks ("who sees and spends what?"), so they start together.
- **Timing.** Post 13 can't fully show Claude through a cloud until a quota or verification
  comes through. It can be written now, with the Vertex quota refusal as an honest result, and
  updated when a call succeeds.
