# Payment Gateway Simulator

Portfolio project demonstrating asynchronous payment processing with Java 25, Spring Boot, PostgreSQL, and Redis.

## Architecture

The application follows Clean Architecture: the domain has no Spring or persistence dependency, use cases depend on ports, and HTTP/JDBC/bank simulation are adapters.

```text
domain/model/              Money and payment state transitions
application/port/in/       Payment and callback use cases
application/port/out/      Persistence, bank, and delivery contracts
application/service/       Payment creation, async processing, circuit breaker
adapter/in/web/            Payment API and signed webhook endpoint
adapter/out/persistence/   PostgreSQL stores and transactional inbox/outbox
adapter/out/bank/          Deterministic bank and signed callback adapters
adapter/out/messaging/     Scheduled outbox/inbox workers
infrastructure/            Spring bean wiring and resilience configuration
```

### Payment flow

1. `POST /api/v1/payments` atomically stores the payment, the merchant-scoped idempotency record, and a `PAYMENT_SUBMITTED` outbox event.
2. A background worker claims outbox rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, sends the request to the deterministic fake-bank adapter, and records each attempt.
3. The fake bank sends a signed HMAC-SHA256 callback to the webhook endpoint. The endpoint verifies the signature, persists the event to the inbox, deduplicates provider event IDs, and acknowledges without applying the payment result synchronously.
4. A separate worker processes the inbox and applies a valid authorization or decline. Amount and currency are checked against the payment; terminal payment states are never overwritten.
5. Transient failures are retried with persisted exponential backoff and jitter. A circuit breaker opens after consecutive bank failures, allows a single half-open probe after its cool-down, and returns to closed after recovery. Exhausted attempts are retained as `DEAD` for inspection.

The fake bank authorizes amounts below 1,000,000 minor units and declines larger amounts with `simulated_limit_exceeded`. Set `BANK_SIMULATOR_FAIL_REQUESTS=true` to simulate an unavailable bank and exercise retry/circuit-breaker behavior. The simulator is intentionally deterministic and is not a real payment processor.

Redis applies a distributed fixed-window limit of 60 payment-creation requests per merchant per minute. PostgreSQL remains authoritative for idempotency; clearing Redis never permits a duplicate charge.

## Local dependencies

Requirements: Docker Compose, JDK 25, and Maven 3.9 or newer. Copy the example environment file, then start the stack:

```bash
Copy-Item .env.example .env
docker compose up --build -d
```

The `.env` file contains local development values only and is ignored by Git. Replace its example password and webhook secret before starting. PostgreSQL and Redis ports bind to loopback; the API is available at `http://localhost:8080`.

Create a demo merchant after Flyway has initialized PostgreSQL:

```bash
docker compose exec -T postgres psql -U gateway -d payment_gateway -c "INSERT INTO merchants (id, name, status) VALUES ('11111111-1111-4111-8111-111111111111', 'Demo Merchant', 'ACTIVE') ON CONFLICT (id) DO NOTHING;"
```

Create a payment. Repeating the same request with the same idempotency key replays the original `202` response; changing the amount or currency with that key returns `409 Conflict`.

```bash
curl -i http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "X-Merchant-Id: 11111111-1111-4111-8111-111111111111" \
  -H "Idempotency-Key: order-2026-0001" \
  -d '{"amountMinor":1299,"currency":"BRL"}'
```

The initial response is `202 Accepted` with status `PENDING`. Repeating the same body and key returns the same payment ID; changing the amount or currency with that key returns `409 Conflict`.

Fetch the payment using the same merchant scope. The status becomes `AUTHORIZED` or `DECLINED` after the asynchronous bank callback is processed:

```bash
curl -i http://localhost:8080/api/v1/payments/<payment-id> \
  -H "X-Merchant-Id: 11111111-1111-4111-8111-111111111111"
```

### Webhook contract

`POST /api/v1/webhooks/fake-bank` expects the raw JSON event body and an `X-Bank-Signature` header containing the lowercase hexadecimal HMAC-SHA256 of the exact body bytes, signed with `BANK_WEBHOOK_SECRET`. Invalid signatures receive `401`; a new valid event receives `202`; a duplicate provider event ID receives `200` and is not enqueued again. Use the same key for the simulator and API.

The merchant header is a demo tenant selector, not authentication. Do not expose the simulator publicly without real authentication and authorization. The local HMAC secret and database password must be replaced in any shared or deployed environment.

## Build and test

```bash
mvn --batch-mode verify
```

Run the API with `docker compose up --build -d`. To run it directly with Maven, start PostgreSQL and Redis first, then export `DATABASE_PASSWORD`, `REDIS_HOST`, and `BANK_WEBHOOK_SECRET` in the shell. Integration tests use Testcontainers and run automatically when Docker is available; they are skipped when Docker is not installed.

## CI and container

GitHub Actions runs `mvn verify` on Java 25 for pushes and pull requests to `main`. `Dockerfile` uses a multi-stage build and a non-root runtime user. `docker compose` starts PostgreSQL, Redis, and the API with health checks.

## Data and security

Amounts are represented as integer minor units (for example, cents), never floating-point numbers. Cardholder data is out of scope; do not store card numbers, security codes, credentials, or real payment-provider secrets in this simulator.
