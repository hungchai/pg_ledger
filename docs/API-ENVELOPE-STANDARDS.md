# API Envelope Standards

**Status**: Draft  
**Scope**: All services in the platform. New endpoints follow this standard; existing endpoints migrate when touched.

---

## Table of Contents

1. [API Request/Response Standard](#1-api-requestresponse-standard)
   - [1.1 Principles](#11-principles)
   - [1.2 HTTP Method Rules](#12-http-method-rules)
     - [Standard Endpoints (every microservice)](#standard-endpoints-every-microservice)
   - [1.3 Headers](#13-headers)
     - [Auth Modes](#auth-modes)
     - [Signature Scheme (when enabled)](#signature-scheme-when-enabled)
     - [Auth Examples](#auth-examples)
   - [1.4 Request Body](#14-request-body)
   - [1.5 Response Envelope](#15-response-envelope)
   - [1.6 Error Object](#16-error-object)
   - [1.7 HTTP Status Usage](#17-http-status-usage)
   - [1.8 Idempotency Contract](#18-idempotency-contract)
   - [1.9 Client Handling Rules](#19-client-handling-rules)
   - [1.10 Do / Don't](#110-do--dont)

---

## 1. API Request/Response Standard

### 1.1 Principles

1. **HTTP is transport only.** HTTP status code never carries business outcome. Business success/failure lives in the response body.
2. **POST-first.** All business endpoints use POST.
3. **Every write is idempotent** via caller-generated `requestId`.

#### Why This Matters

**Why not lean on HTTP status codes for business errors?**

- **Load balancers and gateways misread them.** LBs commonly retry `5xx`, circuit breakers trip on error rates. A business rejection (`INSUFFICIENT_BALANCE`) surfacing as `4xx`/`5xx` inflates error-rate metrics, triggers false circuit breaks, and can even cause a rejected posting to be auto-retried elsewhere.
- **Ambiguity at aggregation layers.** Observability tooling counts by HTTP code; a service at 10% `422` (all business rejections, all healthy) is indistinguishable from one at 10% `4xx` caused by a gateway bug. Transport health and business health get mixed into one number.
- **One parsing path for clients.** Client code reads exactly one thing: `result`. No matrix of "which codes mean what per endpoint," no divergence between services. This is exactly how major payment APIs work (e.g., Stripe-style `200 + outcome in body`).
- **Stable contract.** HTTP-code semantics drift by deployment (LB rewrites, proxy-injected errors, custom error pages replacing bodies). The envelope survives all of that — the body either arrived with `result`, or it didn't.

**Why POST-first (not RESTful GET/PUT/DELETE)?**

- **Uniform pipeline.** One validation → auth → logging → idempotency path for every endpoint. REST spreads these across method-specific handlers, and each variant is a chance for inconsistency.
- **Bodies instead of query strings.** Complex filters, batch queries (200+ accounts) don't fit URLs. Query-string params are stringly-typed, length-capped, unvalidatable by schema.
- **Simpler signature story.** `GET /accounts/{id}?a=1&b=2` signs path + query + no body; `POST` always signs path + body hash — one canonical signing rule, no per-method branches.
- **Auditing.** Every business request is a body in the access log with a `requestId`. Query strings leak into LB logs, browser history, and intermediate proxies.

**Why caller-generated `requestId` in every body?**

- Retries are the norm in distributed systems. Server-generated IDs make retry = duplicate booking. Caller-generated IDs make retry = safe replay.
- It doubles as the natural idempotency key and correlates every log line, event, and stored record for one logical request.

---

### 1.2 HTTP Method Rules

| Rule | Detail |
|---|---|
| Business endpoints (writes AND queries) | `POST` — always |
| Health / metrics / ops diagnostics | `GET` allowed |

Why POST-first:

- One uniform validation / auth / logging pipeline for every endpoint
- No URL length limit — batch queries (200+ accounts) fit naturally
- Complex filter params stay in a typed JSON body, not query strings
- No intermediate cache / CDN interference on query results

#### REST vs POST-Envelope: deliberate trade-off

| | RESTful (GET/PUT/DELETE) | POST-envelope (this standard) |
|---|---|---|
| Semantics / cacheability | Better — verbs are standard, GET is cacheable | Worse — opaque POSTs, no HTTP cache |
| Public ecosystem fit | Better — browsers, tools "get" REST | Worse — needs client SDK/docs |
| Uniformity across services | Worse — each method = its own handler path | Better — one pipeline everywhere |
| Complex/batch queries | Worse — query-string limits | Better — typed JSON body |
| Retry safety | Worse — PUT/DELETE retry semantics vary | Better — `requestId` replay |
| LB/gateway behavior on errors | Worse — code-triggered retries/breakers | Better — business errors never touch LB logic |
| Internal ledger domain fit | Worse — double-entry postings aren't CRUD resources | Better — commands fit POST naturally |

Verdict: for **public, resource-oriented, cacheable** APIs → REST is the right tool. For **internal, command-oriented, financial** APIs where every request is an idempotent command → POST-envelope wins. That is this platform. Keeping GET only for the standard health/metrics endpoints preserves what REST is actually good for here: infra probes.

#### Standard Endpoints (every microservice)

Every service in the platform MUST expose these GET endpoints. Same path, same contract, on every service — load balancers, Kubernetes probes, and ops tooling can depend on them blindly.

| Endpoint | Purpose | Contract |
|---|---|---|
| `GET /health` | Liveness | `200` + `{"status":"UP"}` when the process is up; no dependency checks. LB/k8s liveness probe target |
| `GET /health/ready` | Readiness | `200` + `{"status":"UP"}` when ready to serve (DB connected, migrations applied, caches loaded); `503` otherwise. k8s readiness probe target |
| `GET /metrics` | Prometheus scrape | Prometheus text format. Internal network only |

Rules:

- Health endpoints bypass auth — never require API key or signature
- Health responses carry no business data
- `/health` failing = process crash-loop candidate; `/health/ready` failing = do not route traffic here yet
- Degraded-but-alive (e.g., replica reader lagging) → `ready` still `UP`, degradation surfaced via `/metrics`

---

### 1.3 Headers

| Header | Required | Description |
|---|---|---|
| `Content-Type: application/json` | Yes | Only JSON is accepted |
| `X-Trace-Id` | No | Caller trace ID; server generates one if absent |
| `X-Api-Key` | Only if auth enabled | Caller's API key (identifies the caller) |
| `X-Signature` | Only if auth enabled | Request signature (proves the key holder sent this exact request) |

#### Auth Modes

Services run behind a trusted internal network. Auth is **disabled by default** and enabled by configuration — the API contract supports both modes from day one, so enabling it later breaks no clients that follow this standard.

| Mode | Headers | Who validates |
|---|---|---|
| Off (default) | none | — |
| API key only | `X-Api-Key` | Gateway/filter layer |
| API key + signature | `X-Api-Key` + `X-Signature` | Gateway/filter layer |

Auth is a gateway/filter concern. Business code never reads, validates, or branches on auth headers. Keys and secrets never appear in URL or body.

#### Signature Scheme (when enabled)

- Algorithm: `HMAC-SHA256`, hex-encoded
- Key: per-caller secret, provisioned out-of-band
- Signing input — the exact bytes sent, in this order:

```
apiKey + "\n" + method + "\n" + path + "\n" + bodySha256Hex + "\n" + timestamp
```

| Component | Source |
|---|---|
| `apiKey` | value of `X-Api-Key` |
| `method` | HTTP method, uppercase (`POST`) |
| `path` | URL path with query string, e.g. `/api/v1/postings` |
| `bodySha256Hex` | hex SHA-256 of the raw request body bytes; empty body → SHA-256 of empty string |
| `timestamp` | Unix epoch seconds, sent as `X-Timestamp` header |

Additional headers when signing:

| Header | Required | Description |
|---|---|---|
| `X-Timestamp` | Yes, when signing | Unix epoch seconds. Reject if `abs(serverTime - timestamp) > 300` — blocks replay |

Rules:

- Signature and timestamp headers are included in every request once the mode is on; there is no per-endpoint opt-out
- Server rejects with `401` (bad key/signature) or `408`-semantics stale timestamp (`401` with `errorCode: AUTH_TIMESTAMP_INVALID` at gateway level) — never with a `200 result:FAIL`, since the request never reaches the business layer
- Retrying a failed request (same body) reuses the same `requestId` but needs a fresh `timestamp` + re-sign

#### Auth Examples

**Mode Off** — no auth headers:

```json
POST /api/v1/postings
Content-Type: application/json

{ "requestId": "0192f4a0-...", "...": "..." }
```

**API key only** — add one header:

```json
POST /api/v1/postings
Content-Type: application/json
X-Api-Key: ak_live_7f3d9b2c

{ "requestId": "0192f4a0-...", "...": "..." }
```

**API key + signature** — full signing walkthrough.

Request being sent:

```json
POST /api/v1/postings
Content-Type: application/json

{ "requestId": "0192f4a0-6c8e-7abc-8def-0242ac170001" }
```

Caller computes, with secret `sk_live_a1b2c3d4e5`:

```bash
bodySha256=$(printf '%s' '{"requestId":"0192f4a0-6c8e-7abc-8def-0242ac170001"}' | shasum -a 256 | cut -d' ' -f1)

printf '%s\n%s\n%s\n%s\n%s' \
  "ak_live_7f3d9b2c" \
  "POST" \
  "/api/v1/postings" \
  "$bodySha256" \
  "1760000000" \
  | openssl dgst -sha256 -hmac "sk_live_a1b2c3d4e5" -hex
# → 4f9c1d...  (this value goes in X-Signature)
```

Final request:

```json
POST /api/v1/postings
Content-Type: application/json
X-Api-Key: ak_live_7f3d9b2c
X-Timestamp: 1760000000
X-Signature: 4f9c1d...

{ "requestId": "0192f4a0-6c8e-7abc-8def-0242ac170001" }
```

Points the example makes explicit:

- Sign the **raw body bytes exactly as sent** — re-serializing JSON client-side changes the hash and the signature fails
- The secret (`sk_...`) never leaves the caller; only the key id (`ak_...`) travels in a header
- Same body retried at second 1760000045: same `requestId`, new `X-Timestamp: 1760000045`, re-computed `X-Signature`

---

### 1.4 Request Body

Mandatory fields:

| Field | Type | Rule |
|---|---|---|
| `requestId` | `string` | Biz request ID. Caller-generated, UUID v7, globally unique. Same logical request re-sent on retry reuses the same `requestId` |

Business parameters follow. Every request body — including queries — carries `requestId`.

```json
POST /api/v1/balances/query
{
  "requestId": "0192f4a0-6c8e-7abc-8def-0242ac170001",
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD"
}
```

---

### 1.5 Response Envelope

Transport success is always `HTTP 200` — for business success AND business failure:

```json
HTTP 200
{
  "result": "OK",
  "body": [ {}, {}, {} ]
}
```

| Field | Type | Rule |
|---|---|---|
| `result` | `enum` | `OK` or `FAIL`. Nothing else |
| `body` | `array` | Always an array. Single item → one-element array. No result → `[]` |

**`body` shape by outcome:**

| result | body content |
|---|---|
| `OK` | Result object(s); `[]` when the operation legitimately returns nothing |
| `FAIL` | One or more error objects (see 1.6) |

**Success example:**

```json
HTTP 200
{
  "result": "OK",
  "body": [
    {
      "accountId": "CLIENT_ACC_001",
      "balanceType": "AVAILABLE_BALANCE",
      "currency": "USD",
      "amount": "200000.00"
    }
  ]
}
```

**Business failure example (still HTTP 200):**

```json
HTTP 200
{
  "result": "FAIL",
  "body": [
    {
      "errorCode": "INSUFFICIENT_BALANCE",
      "errorMessage": "DEBIT 800000.00 exceeds available balance 200000.00",
      "context": {
        "accountId": "CLIENT_ACC_001",
        "balanceType": "AVAILABLE_BALANCE",
        "currency": "USD",
        "required": "800000.00",
        "available": "200000.00"
      }
    }
  ]
}
```

---

### 1.6 Error Object

```json
{
  "errorCode": "SCREAMING_SNAKE_CASE",
  "errorMessage": "Human-readable detail",
  "field": "legs[0].lines[1].amount",
  "context": { }
}
```

| Field | Required | Description |
|---|---|---|
| `errorCode` | Yes | From the platform error code registry. Stable contract |
| `errorMessage` | Yes | Human-readable. Diagnostic only — clients must NOT string-match it |
| `field` | No | Request field the error points to, JSON-path style |
| `context` | No | Structured details (amounts, IDs) for programmatic handling |

Multiple simultaneous violations → multiple error objects in `body`, order unspecified.

---

### 1.7 HTTP Status Usage

Non-200 codes are infra-level only and must never be produced by business logic:

| Code | Meaning | Produced by |
|---|---|---|
| `200` | Request reached business layer; check `result` for outcome | App |
| `401` | Auth failure — bad/missing API key, bad signature, or stale `X-Timestamp` (only when auth enabled) | Gateway |
| `403` | Key valid, endpoint not permitted for this key (only when auth enabled) | Gateway |
| `404` | Unknown path | Gateway |
| `503` | Node unavailable (e.g., DB unreachable) | App/Infra |
| `5xx` | Unhandled system error | Infra |

Client rule: **non-200 → no business meaning.** Treat as retryable transport/infra failure.

---

### 1.8 Idempotency Contract

| Scenario | Server behavior |
|---|---|
| `requestId` first seen | Execute, cache response under `requestId` |
| `requestId` seen, previous `COMPLETED` | Return cached response verbatim; no re-execution |
| `requestId` seen, previous still processing | `result: FAIL`, `errorCode: PROCESSING` (retry later) |

Idempotency cache lifetime: minimum 24h.

---

### 1.9 Client Handling Rules

```
receive response
├─ HTTP != 200        → infra failure. Retry same requestId with backoff
│                       (401 when auth on: fix key/signature, not a retry)
├─ result == "OK"     → parse body
└─ result == "FAIL"   → map errorCode; do NOT blind-retry
                         (business rejection — a corrected request needs a NEW requestId)
```

---

### 1.10 Do / Don't

| Do | Don't |
|---|---|
| `POST` for all business endpoints | `GET` with business params in query string |
| Auth headers (`X-Api-Key`, `X-Signature`, `X-Timestamp`) at gateway level | Keys/secrets in URL, body, or business code |
| `requestId` in every request body | Server-generated request IDs |
| `result: OK/FAIL` carries business outcome | `400`/`422` for business rejections |
| `body` always an array | Wrapping single results as bare objects |
| Clients switch on `errorCode` | Clients string-match `errorMessage` |
