# Cafe Management System

🇬🇧 English is expanded by default below — 🇻🇳 nhấn vào phần "Tiếng Việt" bên dưới để mở nội dung tiếng Việt.

<details open>
<summary><strong>🇬🇧 English</strong></summary>

Cafe management web app — microservices architecture (Spring Boot + Angular + PostgreSQL + Kafka), built primarily as a learning project for canonical microservice patterns rather than to optimize for the shortest path to a working app.

The full design — domain model, service boundaries, order saga, routing, docker-compose — is covered by the sections below. For current milestone status and what's planned next, see the [Cafe Roadmap](https://claude.ai/code/artifact/45eea53a-1a1a-4dfe-88bc-f1a1fae63a07?org=ab443343-5dd7-4698-b7cc-00e521059318).

## Services

| Service | Port | Responsibility |
|---|---|---|
| ~~eureka-server~~ | — | **Retired 2026-09** — service discovery registry; see [Retired components](#retired-components) |
| ~~config-server~~ | — | **Retired 2026-09** — centralized configuration (Spring Cloud Config, native profile); see [Retired components](#retired-components) |
| gateway | 8080 | Single entry point for the frontend — routing, CORS, JWT verification |
| auth-service | 8081 | User accounts, login, JWT issuance |
| menu-service | 8082 | Categories & menu items |
| order-service | 8083 | Dining tables, orders, order saga orchestration |
| inventory-service | 8084 | Ingredients, stock levels, recipes, stock reservation (saga participant) |
| report-service | 8085 | Scaffolded module, not yet implemented |
| postgres | 5432 | One database (and one DB role) per service |
| kafka | 9092 / 9094 | Event backbone for the order↔inventory saga |
| kafka-ui | 8090 | Web UI for inspecting Kafka topics |
| zipkin | 9411 | Distributed tracing UI — inspect a request's full trace across every service |

Each of auth/menu/order/inventory-service exposes Swagger UI at `http://localhost:<port>/swagger-ui.html` for interactive API docs.

## Service communication

```mermaid
graph TB
    FE["Angular Frontend :4200"]
    GW["Gateway :8080"]

    subgraph Services["Domain Services"]
        AUTH["auth-service :8081"]
        MENU["menu-service :8082"]
        ORDER["order-service :8083"]
        INV["inventory-service :8084"]
        REPORT["report-service :8085 (stub)"]
    end

    subgraph Infra["Infrastructure"]
        KAFKA[("Kafka :9092")]
    end

    FE -->|HTTP| GW
    GW -->|"/api/auth/**, /api/users/**"| AUTH
    GW -->|"/api/categories/**, /api/menu-items/**"| MENU
    GW -->|"/api/tables/**, /api/orders/**"| ORDER
    GW -->|"/api/ingredients/**, /api/menu-items/*/recipe"| INV
    GW -.->|"/api/reports/** (no handler yet)"| REPORT

    ORDER -->|"WebClient, sync, CircuitBreaker + Retry"| MENU

    ORDER -->|"1. reserve-stock.command, via Kafka"| INV
    INV -->|"2. stock-reservation.reply, via Kafka"| ORDER
    ORDER -->|"3. commit-stock.command, via Kafka"| INV
    INV -->|"4. stock-commit.reply, via Kafka"| ORDER
    ORDER -->|"release-stock.command, via Kafka (branch: only if cancelled after step 2)"| INV

    ORDER -.->|"order.paid, via Kafka (no consumer yet)"| KAFKA

    linkStyle 0,1,2,3,4,5 stroke:#4C6EF5,color:#4C6EF5
    linkStyle 6 stroke:#F08C00,color:#F08C00
    linkStyle 7,8,9,10,11,12 stroke:#9C36B5,color:#9C36B5
```

Edge color marks the kind of communication: 🟦 blue for gateway HTTP routing, 🟧 orange for the direct synchronous service-to-service call, 🟪 purple for Kafka messaging. Solid arrows carry actual request/business traffic; dashed arrows are infrastructure plumbing or paths that exist but have no consumer/handler yet. Note that `order-service → menu-service` is a direct service-to-service call to a fixed host:port — it bypasses the gateway, since the gateway is only the entry point for frontend traffic. (Until 2026-09 this diagram also showed a ⬜ grey "discover + fetch config" edge from every service to a shared Eureka + Config Server node — retired, see [Retired components](#retired-components).) Kafka topics (`reserve-stock.command`, `stock-reservation.reply`, `commit-stock.command`, `stock-commit.reply`, `release-stock.command`, `order.paid`) are drawn as a single edge between publisher and consumer labeled with the topic name, rather than as separate producer→Kafka and Kafka→consumer hops — Kafka is still the broker underneath, this just keeps the diagram from having to route every topic through the `Kafka` node explicitly. The `1.`–`4.` prefixes on the order-service ↔ inventory-service edges are the order they fire in during a normal checkout-then-payment (this diagram is a static topology, not a timeline, so a plain edge can't otherwise convey that); `release-stock.command` is unnumbered since it's a separate branch, only published if a `CONFIRMED` order gets cancelled. For the full step-by-step, including every failure path, see [Business flow: checkout and payment saga](#business-flow-checkout-and-payment-saga) below.

## Building an order

Item selection happens entirely on the frontend before anything is persisted: picking an AVAILABLE table calls `POST /api/tables/{id}/occupy` immediately (so two staff members can't both start building on the same table), but the `Order` itself isn't created yet — the POS screen holds picks in a local draft cart, capped at 50 distinct items (the POS UI blocks adding a 51st once the cap is hit; `CreateOrderRequest`/`CheckoutRequest` enforce the same limit server-side). Staff submit that whole draft in one call when they hit Confirm: `POST /api/orders` (a brand-new order, body `{tableId, items}`) or `POST /api/orders/{id}/checkout` (retrying a failed order, same `items` shape) — both immediately start the verify leg described below, in the same transaction as creating/updating the order. Editing before Confirm is purely local frontend state — no server call happens per item pick.

A table's *current* order (what staff see on clicking an OCCUPIED table) is picked by `OrderRepository.findCurrentByTableId`, filtered on `releasedAt IS NULL` rather than by status alone: a `PAID` order legitimately stays current until the table is explicitly released (pay-first-then-dine — paying doesn't free the table by itself), but once a table *is* released, re-occupying it for a new visit must never resurface that old order again. `DiningTableService.release()` stamps every order still tied to the table with `releasedAt` (`OrderRepository.markReleased`) the moment it actually frees the table, so `releasedAt IS NULL` is the one condition that reliably tells "this order belongs to the table's current occupancy" apart from "left over from a prior, already-settled visit." When more than one order can still satisfy that (two orders created in the same near-simultaneous race, sharing an identical `createdAt`), the highest `id` deterministically wins the tie rather than leaving it to whatever row order Postgres happens to return.

## Business flow: checkout and payment saga

The topology diagram above shows *who talks to whom*; this shows the *order* the steps happen in, including every failure path. `order-service` runs this as an orchestrated state machine (not choreography), structured as two separate saga legs — verify and payment — since stock handling is the only part that can fail and needs compensation.

Stock is handled as a **soft reservation**, not a single deduction: verifying an order *holds* quantity (`Ingredient.reservedQuantity`) without touching `currentStock`; only paying actually deducts it. Availability for a new reservation is always `currentStock - reservedQuantity`, so two in-flight orders can never both claim the same physical stock.

```mermaid
sequenceDiagram
    actor Customer
    participant OS as order-service
    participant K as Kafka
    participant IS as inventory-service (listener)
    participant OP as OutboxPoller (order-service)
    participant Poller as InboxPoller
    participant Job as OrderSagaReconciliationJob

    Note over OS,IS: Verify leg - soft-reserve stock
    Customer->>OS: POST /api/orders/{id}/checkout
    activate OS
    OS->>OS: Order -> PENDING_CONFIRMATION<br/>saga -> STARTED -> STOCK_RESERVATION_REQUESTED<br/>enqueue OutboxMessage (PENDING) - one transaction
    OS-->>Customer: 202 Accepted
    deactivate OS

    loop OutboxPoller sweep (poll-interval)
        OP->>OP: claim next PENDING batch<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate OP
        OP->>K: publish reserve-stock.command (correlationId)
        OP->>OP: mark OutboxMessage PUBLISHED (broker ack received)
        deactivate OP
    end

    K->>IS: deliver reserve-stock.command
    activate IS
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    deactivate IS

    loop InboxPoller sweep (poll-interval)
        Poller->>Poller: claim next PENDING batch<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate Poller
        alt sufficient stock (currentStock - reservedQuantity)
            Poller->>Poller: reservedQuantity += required (currentStock untouched)
            Poller->>K: publish stock-reservation.reply (success)
        else insufficient stock
            Poller->>K: publish stock-reservation.reply (failure, reason)
        end
        Poller->>Poller: mark InboxMessage PROCESSED (result stored)
        deactivate Poller
    end

    K->>OS: deliver stock-reservation.reply
    activate OS
    alt success
        OS->>OS: Order -> CONFIRMED<br/>saga -> CONFIRMED
    else failure
        OS->>OS: compensate: Order -> OPEN<br/>saga -> COMPENSATED
    end
    deactivate OS

    Note over OS,IS: Payment leg - commit the hold
    Customer->>OS: POST /api/orders/{id}/pay
    activate OS
    OS->>OS: Order -> PAYMENT_PENDING<br/>saga -> PAYMENT_REQUESTED (fresh correlationId)<br/>enqueue OutboxMessage (PENDING) - one transaction
    OS-->>Customer: 202 Accepted
    deactivate OS

    loop OutboxPoller sweep (poll-interval)
        OP->>OP: claim next PENDING batch<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate OP
        OP->>K: publish commit-stock.command (correlationId)
        OP->>OP: mark OutboxMessage PUBLISHED (broker ack received)
        deactivate OP
    end

    K->>IS: deliver commit-stock.command
    activate IS
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    deactivate IS

    loop InboxPoller sweep (poll-interval)
        Poller->>Poller: claim next PENDING batch<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate Poller
        Poller->>Poller: currentStock -= required<br/>reservedQuantity -= required<br/>record StockMovement
        Poller->>K: publish stock-commit.reply (success)
        Poller->>Poller: mark InboxMessage PROCESSED (result stored)
        deactivate Poller
    end

    K->>OS: deliver stock-commit.reply
    activate OS
    alt success
        OS->>OS: Order -> PAID<br/>saga -> COMPLETED<br/>enqueue OutboxMessage (order.paid) - same transaction
        OP->>K: (async, same OutboxPoller loop as above) publish order.paid
    else failure (rare - the hold was already validated at reserve time)
        OS->>OS: revert: Order -> CONFIRMED<br/>saga -> CONFIRMED
    end
    deactivate OS

    Note over OS,Job: Reconciliation - either leg, every sweep-interval (30s)
    Job->>Job: find sagas stuck at STOCK_RESERVATION_REQUESTED<br/>or PAYMENT_REQUESTED past stuck-threshold (60s)
    alt verify leg, retries remain
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: enqueue OutboxMessage (reserve-stock, same correlationId)
    else verify leg, retries exhausted
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: compensate: Order -> OPEN
    else payment leg, retries remain
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: enqueue OutboxMessage (commit-stock, same correlationId)
    else payment leg, retries exhausted
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: revert: Order -> CONFIRMED (stock hold stays)
    end

    Note over OS,IS: Cancelling a CONFIRMED order - release the hold
    Customer->>OS: POST /api/orders/{id}/cancel
    OS->>OS: Order -> CANCELLED<br/>enqueue OutboxMessage (release-stock) - same transaction
    OP->>K: (async, same OutboxPoller loop as above) publish release-stock.command
    K->>IS: deliver release-stock.command
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    Poller->>Poller: claim, then reservedQuantity -= required<br/>(currentStock untouched), mark PROCESSED
```

Both legs fail the same two ways:

- **A reply arrives, but says no** — handled directly in the reply listener: `onStockReservationReply` compensates the verify leg back to `OPEN`; `onStockCommitReply` reverts the payment leg back to `CONFIRMED` (the stock hold is still legitimate — only the commit attempt failed, so there's nothing to re-verify, just retry payment).
- **No reply ever arrives** (inventory-service was down, the message was lost) — nothing in the request/reply exchange can detect this on its own. `OrderSagaReconciliationJob` now sweeps both legs (`STOCK_RESERVATION_REQUESTED` and `PAYMENT_REQUESTED`) past `stuck-threshold`, and `retryOrCompensate` branches on which leg it finds: the verify leg gives up to `OPEN` (nothing was ever held), the payment leg gives up to `CONFIRMED` (the hold stays — same reasoning as the reply-arrives-but-fails case).

Retrying either leg is safe to repeat because it re-queues the *same* `correlationId` for that leg into the outbox (a fresh one is minted per leg via `OrderSagaStateService.start`/`startPaymentAttempt`): Kafka keys the message by `orderId`, so every attempt lands in the same partition and is processed in order by inventory-service, whose `inbox_messages` table is keyed on `correlationId` (the Transactional Inbox above) — a redelivery of an already-`PROCESSED` correlationId just gets the stored reply resent instead of the effect being applied twice. Cancelling a `CONFIRMED` order's release is still deliberately **not** covered by reconciliation — it's fire-and-forget with no reply to watch. Transactional Outbox below closes the narrower gap of "the release command was never sent because the process crashed before the live Kafka call" (it's now durably queued in the same transaction as the cancellation), but it doesn't add a reply/compensation leg to release — if inventory-service is down long enough that its own retry budget (`app.outbox.max-attempts`) is exhausted, that release is marked `FAILED` and nothing retries it further.

### Order status × saga step

The two state machines above move together but aren't the same thing: `Order.status` is what the POS UI polls and displays; `OrderSagaState.step` is orchestration bookkeeping the API never exposes directly. This table is every reachable combination and what triggers each transition:

| Consumed message (causes this row) | Order status | Saga step | Published message (queued once marked) | Trigger |
|---|---|---|---|---|
| — | `PENDING_CONFIRMATION` | `STARTED` → `STOCK_RESERVATION_REQUESTED` | `reserve-stock.command` | `POST /api/orders` (new order) or `POST /api/orders/{id}/checkout` (retry after a failed attempt) → `OrderSaga.createAndCheckout`/`startCheckout`: one transaction creates or updates the order with its full submitted item list, moves it to `PENDING_CONFIRMATION`, creates/reuses the saga row (fresh `correlationId`), and queues the `RESERVE_STOCK` outbox message — see [Building an order](#building-an-order) above |
| `stock-reservation.reply` (success) | `CONFIRMED` | `CONFIRMED` | — | inventory-service replies success → `onStockReservationReply` → `markConfirmed` (order + saga together) |
| `stock-reservation.reply` (failure) — or none, on reconciliation timeout | `OPEN` (`failureReason` set) | `COMPENSATED` | — | inventory-service replies failure, **or** `OrderSagaReconciliationJob` exhausts `max-retries` with no reply → `compensateToOpen` + `markCompensated` |
| — | `PAYMENT_PENDING` | `PAYMENT_REQUESTED` | `commit-stock.command` | `POST /pay` → `startPayment`: order → `PAYMENT_PENDING`, same saga row gets a fresh `correlationId` + reset retry count, `COMMIT_STOCK` outbox message queued — same one-transaction shape as checkout |
| `stock-commit.reply` (success) | `PAID` (`closedAt` set) | `COMPLETED` | `order.paid` | inventory-service replies success → `onStockCommitReply` → `markPaid` + `markCompleted`, and an `ORDER_PAID` outbox message is queued in the same transaction |
| `stock-commit.reply` (failure) — or none, on reconciliation timeout | `CONFIRMED` (`failureReason` set) | `CONFIRMED` | — | inventory-service replies failure, **or** reconciliation exhausts retries → `revertToConfirmed` + `markConfirmed` — stock hold stays intact, only the payment attempt is retried |
| — | `CANCELLED` | *(saga row untouched)* | `release-stock.command` (fire-and-forget) | `POST /cancel` → `OrderSaga.cancelOrder`, only from `OPEN` or `CONFIRMED` (blocked while a leg is in flight, blocked once `PAID`); cancelling from `CONFIRMED` also queues a `RELEASE_STOCK` outbox message in the same transaction, with no saga step of its own |

"Consumed message" is the Kafka reply the saga was waiting for that causes the row's transition — blank where the trigger is an HTTP call instead (`POST /checkout`, `/pay`, `/cancel`) or a reconciliation timeout with no message at all. "Published message" is what gets queued to the outbox once the order/saga-state change in that row commits — it's a queue, not a live send: `OutboxPoller` relays it to Kafka asynchronously afterward (see Transactional Outbox below), so there's a short async gap between a row in this table becoming true and the published message actually reaching Kafka.

Two things worth knowing that aren't obvious from the table alone: `shouldIgnoreReply` (see Idempotent Consumer below) treats `COMPLETED`, `COMPENSATED`, **and** the `CONFIRMED` step as terminal/idle for reply-matching purposes — a reply arriving in any of those is necessarily a stale redelivery, since the only thing that could produce a fresh one while at `CONFIRMED` (a commit-stock reply) is never sent until `startPayment` has already moved the step past it. And `SagaStep` also declares a `COMPENSATING` value that no code path currently assigns — it's not part of the live flow, just reserved for a future in-flight compensation state if one is ever needed.

Releasing a table is gated by more than its current order's status: `POST /api/tables/{id}/release` only succeeds once *every* order ever tied to that table is `CANCELLED` or `PAID` — every other status in the table above (`OPEN`, `PENDING_CONFIRMATION`, `CONFIRMED`, `PAYMENT_PENDING`) blocks it, since releasing mid-lifecycle would let a second order start on a table an earlier one still has a real claim on. The release check and the mirror-image check `OrderService` runs to refuse a second order on a table that already has one in progress test the same invariant from opposite ends of a table's lifecycle, expressed via the exact-complement sets `OrderStatus.CLOSED_STATUSES`/`NON_CLOSED_STATUSES` — defined once rather than as two independently-maintained lists.

## Auth flow

1. Client logs in via `POST /api/auth/login` (public, no token required) — auth-service checks credentials and issues an RS256-signed JWT.
2. Every other request carries that JWT as `Authorization: Bearer <token>`.
3. The gateway's `JwtAuthGlobalFilter` is the only place that ever sees or verifies the JWT: it strips any `X-User-*` headers the client tried to set itself (so identity can't be spoofed), verifies the signature with auth-service's public key (supplied via the `APP_JWT_PUBLIC_KEY` env var — fetched live from config-server until it was retired, see [Retired components](#retired-components)), and — only on success — sets trusted `X-User-Id` / `X-Username` / `X-User-Role` headers from the token's claims.
4. Downstream services never see the JWT; they trust the gateway's headers via `common-lib`'s `HeaderAuthenticationFilter`. A missing or invalid token gets a `401` at the gateway, before it ever reaches a domain service.

## Patterns in use

Since this project's purpose is to practice canonical patterns, worth calling out explicitly which ones are implemented so far, grouped by what problem they solve rather than by when they were added. Names follow the common catalog (Chris Richardson's [microservices.io](https://microservices.io/patterns/index.html) covers all of these except Circuit Breaker/Retry, which is Enterprise Integration Patterns territory, and Optimistic Concurrency Control, a general transaction-processing pattern predating microservices) — worth looking up the canonical definition first if a name is unfamiliar, then coming back to see how this codebase applies it.

### Platform

- ~~**Service Discovery** — Eureka (`eureka-server`)~~ **Retired 2026-09**, see [Retired components](#retired-components)
- **API Gateway** — Spring Cloud Gateway, single entry point + CORS + routing
- ~~**Externalized Configuration** — Spring Cloud Config Server, native profile backed by a bind-mounted `config-repo`~~ **Retired 2026-09**, see [Retired components](#retired-components)
- **Trusted Header Authentication** — gateway validates the JWT once and forwards identity via `X-User-Id`/`X-Username`/`X-User-Role` headers; downstream services trust the gateway instead of re-validating (`common-lib`'s `TrustedHeaderAuth`)
- **Database per Service** — separate Postgres database and role per service

### Resilience

- **Circuit Breaker + Retry** — Resilience4j on order-service's calls to menu-service

### Order saga & consistency

- **Orchestrated Saga** — order-service's checkout flow drives a state machine (`OrderSaga`) with two legs: verify (soft-reserve stock, `OPEN`→`CONFIRMED`) and pay (commit the hold, `CONFIRMED`→`PAID`), each its own Kafka round trip that commits or compensates based on the reply; see [Business flow: checkout and payment saga](#business-flow-checkout-and-payment-saga)
- **Try-Confirm/Cancel-style stock reservation** — inventory-service never deducts `currentStock` directly from a checkout attempt. Verifying *tries* a hold (`reservedQuantity`), paying *confirms* it into a real deduction, cancelling a `CONFIRMED` order *cancels* the hold — the same three-step shape as the classic TCC pattern, layered on top of the saga above rather than replacing it
- **Optimistic Concurrency Control** — `DiningTableService.occupy()`/`release()` each guard a table with a single atomic conditional `UPDATE ... WHERE` statement (`DiningTableRepository.occupyIfAvailable`/`releaseIfAllOrdersClosed`) instead of a separate read-then-write. `occupy()`'s `WHERE` checks the table's own status, so two near-simultaneous `occupy()` calls for the same table can't both succeed. `release()`'s `WHERE` checks (via a subquery) that no order tied to the table is still non-closed — closing the race between releasing and an order reaching a non-closed status in between, not a race between two `release()` calls themselves (those would just both harmlessly succeed). A single-row race either way, not saga/TCC's cross-service coordination — a different consistency risk, addressed here with a different technique

### Messaging reliability

These five all defend the same Kafka exchange (the saga above) against the same two hazards — at-least-once redelivery and "the other side never replies" — each in a different, complementary way:

- **Idempotent Consumer** — makes reprocessing a redelivered message safe, without changing what it does.
  - order-service's order saga reply handlers (`OrderSaga.onStockReservationReply`/`onStockCommitReply`) use `OrderSagaStateService.shouldIgnoreReply` for this: it treats `COMPLETED`, `COMPENSATED`, and `CONFIRMED` as terminal for the saga's current attempt, plus a stale-correlationId check for a reply belonging to an attempt already superseded by a fresh one.
  - Why `CONFIRMED` counts as terminal too: it's structurally always an idle "waiting for the next user action" state in this state machine (reachable only from a successful verify leg or a failed/reverted payment leg) — no legitimate reply is ever expected while a saga sits there, so anything arriving in that state must be a redelivery of one already consumed.
  - Stays synchronous, unlike Transactional Inbox below — reply processing here is fast and has no side effect beyond updating the saga's own state.
- **Transactional Inbox** — the fuller, asynchronous sibling to Idempotent Consumer: decouples *receiving* a message from *processing* it, instead of doing both inline on the listener thread.
  - `StockReservationListener`'s three `@KafkaListener` methods only persist the incoming command into `inbox_messages` (status `PENDING`, keyed on `correlationId`) and ack — no business logic runs inline.
  - A separate scheduled worker, `InboxPoller`, claims a batch of `PENDING` rows (`SELECT ... FOR UPDATE SKIP LOCKED`, safe under concurrent pollers) and hands each to `InboxMessageProcessor`, which runs the actual `reserve`/`commit`/`release` step and marks the row `PROCESSED` atomically in one transaction, then publishes the reply (reserve/commit only — release has none).
  - Why this needs its own async worker rather than just running inline on the listener thread: reserving/committing stock involves row locks across multiple ingredients and multi-step validation, not something safe or fast enough to do synchronously on a Kafka consumer thread — Transactional Outbox below has the equivalent split (durable write, then a separate relay), but its relay side is comparatively light (send a stored payload, no business logic), so the asymmetry here is about how much work happens *after* the durable write, not whether one exists.
  - `correlationId` stays the idempotency key: a redelivered command with an already-`PROCESSED` row gets the stored reply resent without re-running business logic (needed so `OrderSagaReconciliationJob`'s retry-with-same-correlationId still gets answered); one still `PENDING`/`PROCESSING`/`FAILED` is simply dropped.
  - A technical failure rolls that attempt's transaction back; the row goes back to `PENDING` for another pass (up to `app.inbox.max-attempts`) or, once exhausted, `FAILED` permanently — silently, by design (see Reconciliation below for why that's safe to leave silent).
- **Transactional Outbox** — the send-side mirror of Transactional Inbox above: makes "commit a state change" and "durably guarantee the message that must follow it" atomic, by writing both to the same database in the same transaction instead of committing the state change and then separately calling Kafka live.
  - order-service's `OrderSaga` writes an `OutboxMessage` row (status `PENDING`) in the *same* transaction as every order/saga-state change that needs a Kafka message to follow it — reserve, commit, release, and the final `order.paid` event. Before this pattern, those were two separate transactions (local commit, then a live `KafkaTemplate.send()`); a crash in between could leave a saga stuck with no command ever sent, invisible to `OrderSagaReconciliationJob` (which only scans steps a *sent* command produces, not the pre-send `STARTED` step). inventory-service's `InboxMessageProcessor` has the same shape for its two reply topics, queuing the reply in the same transaction as the stock mutation + inbox status update it answers.
  - A separate scheduled `OutboxPoller`, one per service, claims a batch of `PENDING` rows the same `SELECT ... FOR UPDATE SKIP LOCKED` way `InboxPoller` does, and hands each to `OutboxMessagePublisher`, which sends it and blocks on Kafka's send future (`app.outbox.publish-timeout`) so the row only flips to `PUBLISHED` once the broker has actually acknowledged it — anything less would just reopen the same dual-write gap this pattern exists to close.
  - Same retry/give-up shape as Transactional Inbox: a failed send goes back to `PENDING` for another sweep (up to `app.outbox.max-attempts`), then `FAILED` permanently. A row stuck `PROCESSING` because the process crashed after the broker ack but before the commit is a known, accepted exposure window, not reclaimed — same trade-off `InboxPoller` already makes on its side.
- **Reconciliation** — `OrderSagaReconciliationJob` sweeps sagas stuck waiting on a reply on *either* saga leg, and retries or compensates them to the right target state per leg (see the business flow above). This is the safety net for "no reply ever arrives" — Idempotent Consumer and Transactional Inbox only handle a reply that *does* eventually show up, whether on time or redelivered.
- **Dead Letter Queue** — inventory-service routes messages that fail for *technical* reasons at the Kafka-receipt layer (bad payload, bugs, DB errors — never a business "insufficient stock" outcome, which is a normal reply, not an exception) to a `.dlq` topic after a short exponential-backoff retry, instead of blocking the consumer on a poison-pill message. Applies uniformly to all three inventory command topics (`reserve-stock`, `commit-stock`, `release-stock`) via one shared error-handler bean, not configured per topic

### Observability

- **Distributed Tracing** — every service exports spans to Zipkin (`http://localhost:9411`) via Micrometer Tracing + Brave; HTTP (gateway routing, WebClient calls) and Kafka produce/consume are auto-instrumented (`spring.kafka.template`/`listener.observation-enabled`), so a request's `traceId` survives every network hop for free.
  - The one hop auto-instrumentation can't bridge on its own: the order saga's async relay threads (`OutboxPoller`→`OutboxMessagePublisher`, `InboxPoller`→`InboxMessageProcessor`) run detached from the Kafka consumer thread that received the triggering message, so there's no live span to inherit there. `OutboxMessage`/`InboxMessage` rows carry a `traceparent` column (W3C format): the *enqueuing* code (`OrderSaga.enqueue`, `StockReservationListener.enqueue`, `InboxMessageProcessor.enqueueReply`) captures the currently-active span into that column at write time, and the *relaying* code (`OutboxMessagePublisher.publishOne`, `InboxMessageProcessor.processOne`) restores it into a fresh child span before doing its work — stitching the async gap back into the same trace instead of starting a disconnected one.
  - A row with no stored traceparent (no live span to capture at enqueue time — e.g. `OrderSagaReconciliationJob`'s scheduled sweep re-queuing a stuck saga) falls back to a fresh root span instead of failing; each reconciliation retry is its own complete, freestanding trace rather than a broken link in the original one.
  - Docker's own health-check polling (`GET /actuator/health`, every few seconds per container) is excluded from tracing on every service. `OrderSagaReconciliationJob`'s recurring sweep gets the same treatment on order-service, via an `ObservationPredicate` bean rather than filtering by observation *name* — every `@Scheduled` method shares the single name `tasks.scheduled.execution` (just like every HTTP request shares `http.server.requests`), so filtering by name would silently suppress tracing for every other scheduled method too, not just this one. The scheduled-poller predicate (`ScheduledPollerObservationPredicates`, package-private in order-service's own `config` package) matches on the observation's target class instead — populated only for tasks Spring wraps via its `@Scheduled` machinery (`ScheduledMethodRunnable`). The outbox/inbox pollers (order-service's and inventory-service's `OutboxPoller`, inventory-service's `InboxPoller`) don't need this predicate and aren't in it: they register their fixed delay via `SchedulingConfigurer`/`ScheduledTaskRegistrar.addFixedDelayTask` instead of `@Scheduled`, so each can source its interval from a bound `@ConfigurationProperties` value rather than a second, separately-defaulted placeholder. That registration path also never produces a `tasks.scheduled.execution` observation in the first place, so there's nothing to filter for them.
  - Excluding the health check by path is less direct than it looks: the predicate runs *before* the request is dispatched to a handler, so `Observation.Context.getPathPattern()` — the resolved route — isn't populated yet at that point and is always `null`. gateway (which doesn't run a Spring Security filter chain) keeps its own local predicate that works around this by matching the *raw* request instead, via `context.getCarrier()` — available immediately, unlike the resolved pattern; it's reactive-context-specific and genuinely can't be shared with the servlet-based predicate the other five services use. (Until their 2026-09 retirement, `config-server` and `eureka-server` each carried an identical copy of this same reactive/servlet-agnostic workaround, deliberately kept unshared — see [Retired components](#retired-components).)
  - On the five services that do run a Spring Security filter chain (auth, menu, order, inventory, report), path-based matching alone isn't enough: Spring Security's own filter-chain and authorization observations are a separate `Observation.Context` type with no path or URI field at all, so no predicate can single them out by inspecting the context. Instead, `HealthCheckMarkingFilter` (`common-lib`, registered ahead of every other observation-producing filter) marks the current thread when the request targets `/actuator/health`; `HealthCheckObservationPredicates.excludingMarkedRequests()` then excludes every observation created on a marked thread regardless of its context type — HTTP-level and Spring-Security-level alike — while real requests, whose thread is never marked, keep full tracing depth.

## Retired components

- **Service Discovery (Eureka)** and **Externalized Configuration (Spring Cloud Config Server)** — both retired 2026-09, as the first step of migrating the deployment target from `docker-compose` to Kubernetes (see the [Cafe Roadmap](https://claude.ai/code/artifact/45eea53a-1a1a-4dfe-88bc-f1a1fae63a07?org=ab443343-5dd7-4698-b7cc-00e521059318) for the in-progress migration). Kubernetes provides both concerns natively — Service DNS for discovery, ConfigMap/Secret for config — so the app-level Eureka/`eureka-server` registry and Spring Cloud Config/`config-server` were removed rather than ported.
- What changed as a result: every inter-service call (gateway's routing table, `order-service`'s call to `menu-service`) now targets a fixed `host:port` instead of a logical name resolved via Eureka; each service's operational config (previously fetched live from `config-server`'s `config-repo`) is now baked directly into that service's own `application.yml` — except secrets (DB usernames/passwords, JWT keys), which are sourced from env vars instead of being written into `application.yml` at all: locally that's the `.env` file `docker-compose.yml` reads via variable substitution (see `.env.example`); in the real deployment it's a K8s Secret synced from GCP Secret Manager.
- Local dev impact: `docker compose up` no longer starts an `eureka-server`/`config-server` container — one less moving part, not a regression. Docker Compose's own DNS still resolves a fixed service name (e.g. `http://menu-service:8082`) for any *other container* on the network exactly as before; the one thing that used to come for free via Eureka and now needs a manual one-time step is reaching a service by name from a process running **bare** (e.g. an IDE) alongside the rest in Docker — see [Troubleshooting](#troubleshooting) below.

## Structure

```
backend/    Maven multi-module reactor: 5 domain services + gateway + common-lib
frontend/   Angular (standalone components)
docker/     Postgres init scripts
charts/     Helm charts for the real GKE deployment: cafe-service (reusable per-service chart)
            + cafe (umbrella chart aliasing it 6 times, one per service)
k8s/        Plain K8s/CNPG/Strimzi manifests for the data layer (Postgres cluster +
            storage class + backups, Kafka cluster) and Helm values overrides for
            cluster-wide operators (currently just Strimzi's)
scripts/    Standalone scripts shared between local use and CI, e.g. image-tag.sh
            (computes a backend service's content-hash image tag)
.github/    GitHub Actions workflows (currently: backend-ci.yml, see Testing below and
            docs/gke-cicd-runbook.md's Step 9)
docs/       Step-by-step setup runbooks (currently: the GKE/CI-CD build, see
            docs/gke-cicd-runbook.md)
```

(Until 2026-09, `backend/` also had `eureka-server` and `config-server` modules — retired, see [Retired components](#retired-components).)

Until config-server's retirement, its native config lived at `backend/config-server/src/main/resources/config-repo/`, bind-mounted read-only into the `config-server` container so editing a `config-repo/*.yml` file only required a restart, no image rebuild. Each service's operational config now lives directly in that service's own `src/main/resources/application.yml` instead — changing it requires rebuilding that service's image. Secrets are the exception: they're left out of `application.yml` and sourced from env vars instead (see [Retired components](#retired-components) for exactly where from), so they can change without a rebuild.

## Prerequisites

- Java 21
- Node.js 20+ (Angular 21 / npm 11)
- Docker & Docker Compose
- Helm & kubectl, and access to a Kubernetes cluster — only needed for the `charts/`/`k8s/` GKE
  deployment, not for running locally via Docker Compose below

## Running locally

```bash
cp .env.example .env   # first time only — supplies DB credentials + JWT keys to docker compose
docker compose up -d
cd frontend && ng serve
```

`docker compose up -d` starts everything backend-side in one shot — Postgres, Kafka, Kafka UI, Zipkin, gateway, and all 5 domain services (`eureka-server`/`config-server` no longer part of the stack, see [Retired components](#retired-components)) — then the frontend dev server runs separately, outside Compose, with hot reload. Common day-to-day commands beyond the initial start:

```bash
docker compose ps                           # what's running, and its health status
docker compose logs -f order-service        # tail one service's logs (Ctrl+C to stop)
docker compose up -d --build                # rebuild + restart all services (mvn package runs its tests first — see Testing below)
docker compose up -d --build order-service  # rebuild + restart one service after a code change (mvn package runs its tests first — see Testing below)
docker compose restart order-service        # restart without rebuilding, e.g. after changing a docker-compose.yml env var, or just to bounce a stuck container
docker compose down                         # stop and remove all containers; the Postgres volume (postgres-data) survives this
docker compose down -v                      # same, but also wipes Postgres data — use for a genuinely clean slate
```

Gateway (the single entry point for the frontend): http://localhost:8080
Kafka UI: http://localhost:8090

There's no self-registration flow — staff accounts are provisioned by an ADMIN. On first boot, auth-service auto-seeds a default admin account (`admin` / `admin123`) if the `users` table is empty, so you have something to log in with. It's dev-only; a real deployment should seed its first admin out-of-band instead. Roles are `ADMIN` and `CASHIER`.

## Testing

Frontend unit tests run on Angular's Vitest-based test builder:

```bash
cd frontend
npm test               # watch mode
npm run test:coverage  # single run, with an HTML coverage report
```

`test:coverage` writes a drill-down report to `frontend/coverage/frontend/index.html` — open it in a browser to see coverage per folder, then per file, then per line (folders/files are clickable, uncovered lines are highlighted red). Project convention: every new or modified component gets unit tests reaching at least 70% coverage before the work is considered done.

Backend unit tests run per-module with Maven (JUnit 5 + Mockito):

```bash
cd backend
mvn -pl inventory-service -am test
```

Some modules are the exception: certain test classes run against a real Postgres and/or Kafka container via [Testcontainers](https://testcontainers.com/) rather than a mock, to actually exercise behavior that a mock can't verify — for the Postgres-backed ones, a real lock serializing concurrent transactions, real Hibernate persistence-context state, custom JPQL/SQL; for the Kafka-backed ones, real broker wiring (JSON deserialization, header extraction, topic dispatch). order-service and inventory-service currently have Postgres-backed tests of this kind, and order-service additionally has a Kafka-backed one, so `mvn -pl <module> -am test` for either module needs a reachable Docker daemon; each of those classes is tagged `@Tag("testcontainers")` so it can be excluded (`-DexcludedGroups=testcontainers`) in contexts without one, e.g. that service's own Docker image build stage. Every service's Docker build runs its unit test suite as part of `mvn package` (no `-DskipTests` anywhere), so `docker compose build <service>` doubles as a test gate, not just a packaging step.

Modules that opt into the `jacoco-maven-plugin` (declared once in the parent `pom.xml`'s `pluginManagement`; `common-lib`, `auth-service`, `menu-service`, `order-service`, and `inventory-service` activate it so far) write a drill-down HTML coverage report on every `mvn test` run, at `<module>/target/site/jacoco/index.html` — e.g. `backend/inventory-service/target/site/jacoco/index.html`. It's a plain static file, not served by anything: open it as a `file://` URL, e.g. `file:///<path-to-repo>/backend/inventory-service/target/site/jacoco/index.html` (substitute your own absolute repo path), or just double-click the file. You'll see coverage per package, then per class, then per line (same drill-down shape as the frontend's report; uncovered lines are highlighted red). To check a different module once it opts in, swap the `-pl` module name and the path accordingly. Each opted-in module sets its own `jacoco.line.coverage.minimum` property — a no-regression ratchet at that module's current coverage, or the parent's 70% default for a module already at or above it — enforced by `mvn jacoco:check`; backend test coverage is being raised module by module rather than all at once, so check the codebase for the current per-module floor instead of treating this README as the tracker.

[`.github/workflows/backend-ci.yml`](.github/workflows/backend-ci.yml) runs a `gitleaks` secret scan on every push and pull request. Its `test` job — which only runs when `backend/**` or `scripts/**` changed (or on a manual `workflow_dispatch`) — additionally runs `spotless:check`, the full `mvn test` reactor, `mvn jacoco:check` against the per-module floors above, and `shellcheck`/a self-test of `scripts/image-tag.sh`. On a push to `master` it also builds and pushes each service's image to Artifact Registry, tagged by content hash (see [`scripts/image-tag.sh`](scripts/image-tag.sh)) — see `docs/gke-cicd-runbook.md`'s Step 9 for the full pipeline, its path-based job gating, and the one-time GCP setup it depends on.

## Code formatting

Backend uses [Spotless](https://github.com/diffplug/spotless) with Google Java Format, declared once (as an active plugin, not just `pluginManagement`) in the parent `backend/pom.xml` — every module inherits it automatically, no per-module opt-in needed:

```bash
cd backend
mvn spotless:check   # fails if a changed file isn't formatted correctly
mvn spotless:apply   # rewrites files in place to fix it
```

Frontend uses [Prettier](https://prettier.io/), configured via `frontend/.prettierrc`:

```bash
cd frontend
npm run format:check
npm run format
```

Both checks run automatically in a `pre-commit` git hook (`.git/hooks/pre-commit` — not tracked by git, since hooks live outside version control; copy it manually into a fresh clone) that blocks a commit if staged code fails formatting. Spotless's `ratchetFrom` setting means only files that differ from `origin/master` are checked, so the pre-existing codebase keeps whatever formatting it already had until a file is touched again — there's no one-time "reformat everything" commit to wade through.

## Troubleshooting

- *(Historical, applied only while the project used Eureka, retired 2026-09 — see [Retired components](#retired-components))* **Gateway returned 503 right after restarting a service** — Spring Cloud Gateway's load balancer kept a short-lived cache of service instances resolved via Eureka; it could go stale for a few seconds after a restart. Gateway now routes to a fixed `host:port` per service, so this class of staleness can no longer happen.
- **Docker build cache eating disk space** — repeated `docker compose build` during iterative development leaves old image layers behind indefinitely. Run `docker builder prune -f` periodically to reclaim space, or `docker system df` to check what's actually using it.
- **Testcontainers-backed tests (order-service's or inventory-service's testcontainers-tagged classes, etc.) fail to connect, complaining about the timezone** — the Postgres JDBC driver asks the server to `SET TIME ZONE` to the JVM's default on connect; on a machine whose OS reports an old IANA alias (e.g. `Asia/Saigon`, superseded by `Asia/Ho_Chi_Minh`), the Testcontainers `postgres:16` image's bundled tzdata doesn't recognize it and refuses the connection outright. Both order-service's and inventory-service's `pom.xml` force `-Duser.timezone=UTC` on their own `maven-surefire-plugin` to sidestep needing every dev machine's OS-level timezone name to be one this exact Postgres image accepts.
- **Edited an already-applied migration file and startup now fails on a Flyway checksum mismatch** — `validate-on-migrate` is on by default (no override in this project) and checksums every migration file's content the first time it runs, then re-checks that checksum on every later startup; editing an already-applied file afterward — even just a comment — changes its checksum and fails validation against what Postgres already recorded. If you ever need to edit an already-applied migration, don't wipe the database to fix this — recompute the recorded checksum instead, via Flyway's own `repair` operation. No `flyway-maven-plugin` is declared in any `pom.xml` here, so invoke it by its full coordinates from that service's module directory:
  ```bash
  mvn org.flywaydb:flyway-maven-plugin:12.4.0:repair -Dflyway.url=jdbc:postgresql://localhost:5432/<db> -Dflyway.user=<user> -Dflyway.password=<password> -Dflyway.locations=filesystem:src/main/resources/db/migration
  ```
- *(Historical, applied only while the project used Eureka, retired 2026-09 — see [Retired components](#retired-components))* **A service couldn't reach another (Eureka lookups hang or 500) when running one bare from an IDE alongside the rest in Docker** — every service's `eureka.instance.hostname` used to default to `host.docker.internal` rather than its auto-detected host IP, because on Windows that auto-detected IP can land on a virtual adapter (VPN/WSL/Hyper-V) that Docker containers can't route to. This whole class of issue (including Docker Desktop periodically rewriting its `host.docker.internal` hosts-file entry) no longer applies now that routing doesn't go through Eureka — see the current entry below for what replaced it.
- **A service can't reach another (connection refused / host not found) when you run one bare from an IDE alongside the rest in Docker** — routes are now fixed hostnames (e.g. `http://menu-service:8082`) instead of Eureka-resolved. Docker Compose's own DNS resolves those names for any *other container* on the network automatically, but a **bare-host** process (e.g. order-service run from Eclipse) isn't on that network, so it can't resolve a plain service name at all. Since Compose already publishes every service's port to the host (`ports:` in `docker-compose.yml`), the fix is a one-time hosts-file alias mapping each service name to `127.0.0.1`, as Administrator:
  ```powershell
  Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "127.0.0.1 auth-service menu-service order-service inventory-service report-service"
  ```
  After that, a bare-run service resolves any other service's name to `127.0.0.1:<its published port>`, same as another container would.
- **A service fails to start when run bare from an IDE** — DB usernames/passwords and JWT keys are blank in `application.yml`, sourced from env vars instead (see [Retired components](#retired-components) for exactly where that comes from) — a bare IDE run simply doesn't have them set. For auth-service/gateway this surfaces as `IllegalArgumentException: "Invalid RSA private key PEM"` / `"Invalid RSA public key PEM"` (parsing an empty PEM string); for the 4 DB-only services it surfaces as `PSQLException: "The server requested SCRAM-based authentication, but no password was provided."`, wrapped in a `BeanCreationException`. Fix: `cp application-local.yml.example application-local.yml` in that service's module directory, then activate the `local` Spring profile (`-Dspring.profiles.active=local` JVM option, or `SPRING_PROFILES_ACTIVE=local` env var) in the IDE's run configuration. `application-local.yml` is gitignored and excluded from the built jar/image (see `backend/pom.xml`) — configuring the IDE to pick it up (e.g. disabling "delegate build/run to Maven" if that setting hides non-Maven-processed resources) is up to each developer's own setup.

</details>

<details>
<summary><strong>🇻🇳 Tiếng Việt</strong></summary>

Ứng dụng quản lý quán cà phê — kiến trúc microservices (Spring Boot + Angular + PostgreSQL + Kafka), được xây dựng chủ yếu như một dự án học tập các pattern microservice kinh điển, thay vì để tối ưu cho việc có ứng dụng chạy được nhanh nhất.

Toàn bộ thiết kế — domain model, ranh giới giữa các service, order saga, routing, docker-compose — được trình bày trong các mục bên dưới. Xem tình trạng milestone hiện tại và kế hoạch sắp tới tại [Cafe Roadmap](https://claude.ai/code/artifact/45eea53a-1a1a-4dfe-88bc-f1a1fae63a07?org=ab443343-5dd7-4698-b7cc-00e521059318).

## Các service

| Service | Port | Trách nhiệm |
|---|---|---|
| ~~eureka-server~~ | — | **Đã retired 2026-09** — registry cho service discovery; xem mục [Thành phần đã retired](#thành-phần-đã-retired) |
| ~~config-server~~ | — | **Đã retired 2026-09** — cấu hình tập trung (Spring Cloud Config, profile native); xem mục [Thành phần đã retired](#thành-phần-đã-retired) |
| gateway | 8080 | Cổng vào duy nhất cho frontend — routing, CORS, xác thực JWT |
| auth-service | 8081 | Tài khoản người dùng, đăng nhập, cấp JWT |
| menu-service | 8082 | Danh mục & món trong menu |
| order-service | 8083 | Bàn ăn, đơn hàng, điều phối order saga |
| inventory-service | 8084 | Nguyên liệu, tồn kho, công thức, giữ chỗ tồn kho (thành viên saga) |
| report-service | 8085 | Module mới scaffold, chưa triển khai |
| postgres | 5432 | Mỗi service có 1 database (và 1 role DB) riêng |
| kafka | 9092 / 9094 | Event backbone cho saga giữa order↔inventory |
| kafka-ui | 8090 | Giao diện web để xem các Kafka topic |
| zipkin | 9411 | Giao diện truy vết phân tán — xem toàn bộ trace của 1 request xuyên suốt các service |

Mỗi service trong số auth/menu/order/inventory-service đều expose Swagger UI tại `http://localhost:<port>/swagger-ui.html` để xem tài liệu API tương tác.

## Giao tiếp giữa các service

```mermaid
graph TB
    FE["Angular Frontend :4200"]
    GW["Gateway :8080"]

    subgraph Services["Domain Services"]
        AUTH["auth-service :8081"]
        MENU["menu-service :8082"]
        ORDER["order-service :8083"]
        INV["inventory-service :8084"]
        REPORT["report-service :8085 (stub)"]
    end

    subgraph Infra["Infrastructure"]
        KAFKA[("Kafka :9092")]
    end

    FE -->|HTTP| GW
    GW -->|"/api/auth/**, /api/users/**"| AUTH
    GW -->|"/api/categories/**, /api/menu-items/**"| MENU
    GW -->|"/api/tables/**, /api/orders/**"| ORDER
    GW -->|"/api/ingredients/**, /api/menu-items/*/recipe"| INV
    GW -.->|"/api/reports/** (no handler yet)"| REPORT

    ORDER -->|"WebClient, sync, CircuitBreaker + Retry"| MENU

    ORDER -->|"1. reserve-stock.command, via Kafka"| INV
    INV -->|"2. stock-reservation.reply, via Kafka"| ORDER
    ORDER -->|"3. commit-stock.command, via Kafka"| INV
    INV -->|"4. stock-commit.reply, via Kafka"| ORDER
    ORDER -->|"release-stock.command, via Kafka (branch: only if cancelled after step 2)"| INV

    ORDER -.->|"order.paid, via Kafka (no consumer yet)"| KAFKA

    linkStyle 0,1,2,3,4,5 stroke:#4C6EF5,color:#4C6EF5
    linkStyle 6 stroke:#F08C00,color:#F08C00
    linkStyle 7,8,9,10,11,12 stroke:#9C36B5,color:#9C36B5
```

Màu của đường nối thể hiện loại giao tiếp: 🟦 xanh dương là routing HTTP qua gateway, 🟧 cam là lời gọi đồng bộ trực tiếp giữa 2 service, 🟪 tím là giao tiếp qua Kafka. Đường liền là traffic nghiệp vụ thật; đường đứt là hạ tầng nền (infra plumbing) hoặc đường đi tồn tại nhưng chưa có consumer/handler xử lý. Lưu ý `order-service → menu-service` là lời gọi trực tiếp giữa 2 service, tới thẳng 1 host:port cố định — không đi qua gateway, vì gateway chỉ là cổng vào cho traffic từ frontend. (Tới trước 2026-09, diagram này còn có 1 đường ⬜ xám "discover + fetch config" từ mỗi service tới 1 node Eureka + Config Server dùng chung — đã retired, xem mục [Thành phần đã retired](#thành-phần-đã-retired).) Các topic Kafka (`reserve-stock.command`, `stock-reservation.reply`, `commit-stock.command`, `stock-commit.reply`, `release-stock.command`, `order.paid`) được vẽ thành 1 đường nối duy nhất giữa publisher và consumer, ghi tên topic ngay trên đó, thay vì tách thành 2 chặng producer→Kafka và Kafka→consumer riêng biệt — Kafka vẫn là broker đứng bên dưới, cách vẽ này chỉ để khỏi phải dẫn mọi topic qua node `Kafka` một cách tường minh. Số thứ tự `1.`–`4.` trên các cạnh giữa order-service ↔ inventory-service thể hiện đúng trình tự chúng xảy ra trong 1 lượt checkout-rồi-thanh-toán bình thường (sơ đồ này là topology tĩnh, không phải timeline, nên 1 cạnh trơn không tự nói lên được điều đó); `release-stock.command` không đánh số vì nó là 1 nhánh riêng, chỉ publish khi đơn đang `CONFIRMED` bị hủy. Muốn xem đầy đủ từng bước, kể cả mọi nhánh lỗi, xem mục "Luồng nghiệp vụ: saga xác thực và thanh toán" bên dưới.

## Xây dựng đơn hàng

Việc chọn món diễn ra hoàn toàn ở frontend, chưa lưu gì cả cho tới khi xác nhận: chọn 1 bàn AVAILABLE sẽ gọi ngay `POST /api/tables/{id}/occupy` (để 2 nhân viên không thể cùng lúc chọn món trên cùng 1 bàn), nhưng bản thân `Order` thì chưa được tạo — màn POS giữ các món đã chọn trong 1 giỏ hàng cục bộ (draft cart), giới hạn tối đa 50 món khác nhau (giao diện POS chặn không cho thêm món thứ 51 khi đã đạt giới hạn; `CreateOrderRequest`/`CheckoutRequest` cũng áp cùng giới hạn này ở phía server). Nhân viên gửi cả giỏ hàng đó lên trong 1 lần gọi duy nhất khi bấm Xác nhận: `POST /api/orders` (đơn hoàn toàn mới, body `{tableId, items}`) hoặc `POST /api/orders/{id}/checkout` (thử lại đơn đã thất bại, cùng cấu trúc `items`) — cả 2 đều khởi động ngay chặng Xác thực mô tả bên dưới, trong cùng 1 transaction với việc tạo/cập nhật đơn. Sửa trước khi Xác nhận chỉ là state cục bộ ở frontend — chưa có lời gọi nào lên server cho từng món được chọn.

Đơn hàng *hiện tại* của 1 bàn (thứ nhân viên thấy khi bấm vào bàn OCCUPIED) được chọn bởi `OrderRepository.findCurrentByTableId`, lọc theo `releasedAt IS NULL` chứ không chỉ dựa vào status: 1 đơn `PAID` vẫn hợp lệ là đơn hiện tại cho tới khi bàn được release tường minh (thanh toán trước, ngồi sau — thanh toán không tự giải phóng bàn), nhưng khi bàn *đã* được release, việc occupy lại bàn đó cho lượt khách mới không được phép làm đơn cũ đó xuất hiện lại. `DiningTableService.release()` đánh dấu `releasedAt` cho mọi đơn còn gắn với bàn (`OrderRepository.markReleased`) ngay tại thời điểm bàn thực sự được giải phóng, nên `releasedAt IS NULL` là điều kiện duy nhất phân biệt đáng tin cậy giữa "đơn này thuộc lượt occupy hiện tại của bàn" và "còn sót lại từ 1 lượt khách trước đã kết thúc". Khi vẫn còn hơn 1 đơn thỏa điều kiện đó (2 đơn được tạo gần như đồng thời, trùng `createdAt`), `id` cao hơn sẽ thắng tie 1 cách xác định (deterministic), thay vì phụ thuộc vào thứ tự row bất kỳ mà Postgres trả về.

## Luồng nghiệp vụ: saga xác thực và thanh toán

Biểu đồ topology ở trên cho biết *ai nói chuyện với ai*; còn biểu đồ này cho biết *thứ tự* các bước diễn ra, kể cả mọi đường đi khi thất bại. `order-service` chạy luồng này dưới dạng một state machine điều phối tập trung (orchestration, không phải choreography), được chia thành 2 chặng saga riêng biệt — xác thực và thanh toán — vì phần xử lý tồn kho là phần duy nhất có thể thất bại và cần compensate.

Tồn kho được xử lý theo mô hình **giữ chỗ mềm (soft reservation)**, không trừ thẳng 1 lần: bước xác thực chỉ *giữ chỗ* số lượng (`Ingredient.reservedQuantity`), không đụng tới `currentStock`; chỉ khi thanh toán mới thực sự trừ. Số lượng khả dụng để giữ chỗ mới luôn là `currentStock - reservedQuantity`, nên 2 đơn đang xử lý cùng lúc không bao giờ giữ trùng cùng 1 phần tồn kho vật lý.

```mermaid
sequenceDiagram
    actor Customer
    participant OS as order-service
    participant K as Kafka
    participant IS as inventory-service (listener)
    participant OP as OutboxPoller (order-service)
    participant Poller as InboxPoller
    participant Job as OrderSagaReconciliationJob

    Note over OS,IS: Chặng Xác thực - giữ chỗ tồn kho (mềm)
    Customer->>OS: POST /api/orders/{id}/checkout
    activate OS
    OS->>OS: Order -> PENDING_CONFIRMATION<br/>saga -> STARTED -> STOCK_RESERVATION_REQUESTED<br/>enqueue OutboxMessage (PENDING) - trong cùng 1 transaction
    OS-->>Customer: 202 Accepted
    deactivate OS

    loop OutboxPoller quét theo poll-interval
        OP->>OP: nhặt batch PENDING kế tiếp<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate OP
        OP->>K: publish reserve-stock.command (correlationId)
        OP->>OP: đánh dấu OutboxMessage PUBLISHED (đã nhận ack từ broker)
        deactivate OP
    end

    K->>IS: deliver reserve-stock.command
    activate IS
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    deactivate IS

    loop InboxPoller quét theo poll-interval
        Poller->>Poller: nhặt batch PENDING kế tiếp<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate Poller
        alt đủ hàng (currentStock - reservedQuantity)
            Poller->>Poller: reservedQuantity += required (currentStock không đổi)
            Poller->>K: publish stock-reservation.reply (success)
        else thiếu hàng
            Poller->>K: publish stock-reservation.reply (failure, reason)
        end
        Poller->>Poller: đánh dấu InboxMessage PROCESSED (lưu kết quả)
        deactivate Poller
    end

    K->>OS: deliver stock-reservation.reply
    activate OS
    alt success
        OS->>OS: Order -> CONFIRMED<br/>saga -> CONFIRMED
    else failure
        OS->>OS: compensate: Order -> OPEN<br/>saga -> COMPENSATED
    end
    deactivate OS

    Note over OS,IS: Chặng Thanh toán - commit phần đã giữ chỗ
    Customer->>OS: POST /api/orders/{id}/pay
    activate OS
    OS->>OS: Order -> PAYMENT_PENDING<br/>saga -> PAYMENT_REQUESTED (correlationId mới)<br/>enqueue OutboxMessage (PENDING) - trong cùng 1 transaction
    OS-->>Customer: 202 Accepted
    deactivate OS

    loop OutboxPoller quét theo poll-interval
        OP->>OP: nhặt batch PENDING kế tiếp<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate OP
        OP->>K: publish commit-stock.command (correlationId)
        OP->>OP: đánh dấu OutboxMessage PUBLISHED (đã nhận ack từ broker)
        deactivate OP
    end

    K->>IS: deliver commit-stock.command
    activate IS
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    deactivate IS

    loop InboxPoller quét theo poll-interval
        Poller->>Poller: nhặt batch PENDING kế tiếp<br/>(SELECT ... FOR UPDATE SKIP LOCKED -> PROCESSING)
        activate Poller
        Poller->>Poller: currentStock -= required<br/>reservedQuantity -= required<br/>ghi StockMovement
        Poller->>K: publish stock-commit.reply (success)
        Poller->>Poller: đánh dấu InboxMessage PROCESSED (lưu kết quả)
        deactivate Poller
    end

    K->>OS: deliver stock-commit.reply
    activate OS
    alt success
        OS->>OS: Order -> PAID<br/>saga -> COMPLETED<br/>enqueue OutboxMessage (order.paid) - trong cùng transaction
        OP->>K: (bất đồng bộ, dùng chung loop OutboxPoller ở trên) publish order.paid
    else failure (hiếm - đã validate từ lúc giữ chỗ)
        OS->>OS: revert: Order -> CONFIRMED<br/>saga -> CONFIRMED
    end
    deactivate OS

    Note over OS,Job: Reconciliation - cả 2 chặng, mỗi sweep-interval (30s)
    Job->>Job: tìm saga kẹt ở STOCK_RESERVATION_REQUESTED<br/>hoặc PAYMENT_REQUESTED quá stuck-threshold (60s)
    alt chặng Xác thực, còn lượt retry
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: enqueue OutboxMessage (reserve-stock, cùng correlationId)
    else chặng Xác thực, hết lượt retry
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: compensate: Order -> OPEN
    else chặng Thanh toán, còn lượt retry
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: enqueue OutboxMessage (commit-stock, cùng correlationId)
    else chặng Thanh toán, hết lượt retry
        Job->>OS: retryOrCompensate(orderId)
        OS->>OS: revert: Order -> CONFIRMED (vẫn giữ chỗ tồn kho)
    end

    Note over OS,IS: Hủy đơn đang CONFIRMED - trả lại chỗ đã giữ
    Customer->>OS: POST /api/orders/{id}/cancel
    OS->>OS: Order -> CANCELLED<br/>enqueue OutboxMessage (release-stock) - trong cùng transaction
    OP->>K: (bất đồng bộ, dùng chung loop OutboxPoller ở trên) publish release-stock.command
    K->>IS: deliver release-stock.command
    IS->>IS: enqueue InboxMessage (PENDING, correlationId)
    IS-->>K: ack
    Poller->>Poller: nhặt, rồi reservedQuantity -= required<br/>(currentStock không đổi), đánh dấu PROCESSED
```

Cả 2 chặng đều xử lý 2 kiểu lỗi giống nhau:

- **Có reply trả về, nhưng báo thất bại** — xử lý trực tiếp trong listener nhận reply: `onStockReservationReply` compensate chặng Xác thực về `OPEN`; `onStockCommitReply` revert chặng Thanh toán về `CONFIRMED` (chỗ giữ tồn kho vẫn hợp lệ — chỉ có bước commit thất bại, nên không cần xác thực lại, chỉ cần thử thanh toán lại).
- **Không có reply nào trả về** (inventory-service bị down, message bị mất) — bản thân cơ chế request/reply không thể tự phát hiện trường hợp này. `OrderSagaReconciliationJob` giờ quét cả 2 chặng (`STOCK_RESERVATION_REQUESTED` và `PAYMENT_REQUESTED`) quá `stuck-threshold`, và `retryOrCompensate` rẽ nhánh theo đúng chặng đang kẹt: chặng Xác thực bỏ cuộc về `OPEN` (chưa từng giữ chỗ gì), chặng Thanh toán bỏ cuộc về `CONFIRMED` (vẫn giữ nguyên chỗ đã giữ — cùng logic như trường hợp reply báo lỗi ở trên).

Retry lại ở chặng nào cũng an toàn vì mỗi lần đều đưa lại *cùng* `correlationId` của chặng đó vào outbox (mỗi chặng có 1 correlationId mới riêng, sinh ra qua `OrderSagaStateService.start`/`startPaymentAttempt`): Kafka key message theo `orderId`, nên mọi lần gửi đều rơi vào cùng 1 partition và được inventory-service xử lý tuần tự; bảng `inbox_messages` dùng `correlationId` làm khóa chính (chính là Transactional Inbox ở trên) — nên khi 1 correlationId đã `PROCESSED` bị gửi lại, nó chỉ nhận lại đúng reply đã lưu, thay vì hiệu ứng bị áp dụng 2 lần. Việc trả chỗ giữ khi hủy đơn `CONFIRMED` vẫn **cố tình không** được Reconciliation theo dõi — đây là fire-and-forget, không có reply để chờ. Transactional Outbox bên dưới đóng lại khoảng trống hẹp hơn là "lệnh release chưa từng được gửi vì process crash trước khi gọi Kafka trực tiếp" (giờ nó được đưa vào hàng đợi bền vững trong cùng transaction với việc hủy đơn), nhưng nó không thêm nhánh reply/compensation nào cho release cả — nếu inventory-service down đủ lâu để hết lượt retry riêng của nó (`app.outbox.max-attempts`), lệnh release đó sẽ bị đánh dấu `FAILED` và không ai thử lại nữa.

### Trạng thái đơn hàng × trạng thái saga

Hai state machine trên di chuyển song song nhưng không phải là một: `Order.status` là thứ POS UI polling và hiển thị; `OrderSagaState.step` là sổ sách điều phối nội bộ, API không bao giờ expose trực tiếp. Bảng dưới đây liệt kê mọi tổ hợp có thể đạt tới và điều gì kích hoạt từng chuyển trạng thái:

| Message nhận vào (gây ra dòng này) | Order status | Saga step | Message publish ra (enqueue sau khi mark) | Kích hoạt bởi |
|---|---|---|---|---|
| — | `PENDING_CONFIRMATION` | `STARTED` → `STOCK_RESERVATION_REQUESTED` | `reserve-stock.command` | `POST /api/orders` (đơn mới) hoặc `POST /api/orders/{id}/checkout` (thử lại sau khi thất bại) → `OrderSaga.createAndCheckout`/`startCheckout`: 1 transaction tạo mới hoặc cập nhật đơn với toàn bộ danh sách item vừa gửi, chuyển đơn sang `PENDING_CONFIRMATION`, tạo/tái dùng dòng saga (correlationId mới), và enqueue message `RESERVE_STOCK` vào outbox — xem mục "Xây dựng đơn hàng" ở trên |
| `stock-reservation.reply` (success) | `CONFIRMED` | `CONFIRMED` | — | inventory-service trả reply thành công → `onStockReservationReply` → `markConfirmed` (cả order lẫn saga) |
| `stock-reservation.reply` (failure) — hoặc không có message nào, khi do reconciliation timeout | `OPEN` (có `failureReason`) | `COMPENSATED` | — | inventory-service trả reply thất bại, **hoặc** `OrderSagaReconciliationJob` hết `max-retries` mà không có reply → `compensateToOpen` + `markCompensated` |
| — | `PAYMENT_PENDING` | `PAYMENT_REQUESTED` | `commit-stock.command` | `POST /pay` → `startPayment`: đơn → `PAYMENT_PENDING`, cùng dòng saga được gán correlationId mới + reset retry count, enqueue message `COMMIT_STOCK` vào outbox — cùng kiểu 1-transaction như checkout |
| `stock-commit.reply` (success) | `PAID` (có `closedAt`) | `COMPLETED` | `order.paid` | inventory-service trả reply thành công → `onStockCommitReply` → `markPaid` + `markCompleted`, đồng thời enqueue message `ORDER_PAID` vào outbox trong cùng transaction |
| `stock-commit.reply` (failure) — hoặc không có message nào, khi do reconciliation timeout | `CONFIRMED` (có `failureReason`) | `CONFIRMED` | — | inventory-service trả reply thất bại, **hoặc** reconciliation hết lượt retry → `revertToConfirmed` + `markConfirmed` — chỗ giữ tồn kho vẫn nguyên, chỉ có lượt thanh toán được thử lại |
| — | `CANCELLED` | *(dòng saga giữ nguyên)* | `release-stock.command` (fire-and-forget) | `POST /cancel` → `OrderSaga.cancelOrder`, chỉ áp dụng từ `OPEN` hoặc `CONFIRMED` (chặn khi đang có 1 chặng saga đang chạy, chặn khi đã `PAID`); hủy từ `CONFIRMED` còn enqueue thêm message `RELEASE_STOCK` vào outbox trong cùng transaction, không có bước saga riêng nào cho việc này |

"Message nhận vào" là reply Kafka mà saga đang đợi, chính là thứ gây ra chuyển trạng thái của dòng đó — để trống ở những dòng mà tác nhân kích hoạt là 1 lời gọi HTTP (`POST /checkout`, `/pay`, `/cancel`) hoặc do reconciliation timeout mà không có message nào cả. "Message publish ra" là thứ được enqueue vào outbox ngay khi thay đổi order/saga-state của dòng đó commit — đây là enqueue vào hàng đợi, không phải gửi thẳng: `OutboxPoller` mới là bên relay nó sang Kafka bất đồng bộ sau đó (xem Transactional Outbox bên dưới), nên sẽ có 1 khoảng trễ ngắn giữa lúc dòng này trở thành đúng và lúc message publish ra thực sự tới được Kafka.

Hai điều đáng biết mà bảng trên không tự nói lên: `shouldIgnoreReply` (xem Idempotent Consumer bên dưới) coi `COMPLETED`, `COMPENSATED`, **và** step `CONFIRMED` là terminal/rảnh khi khớp reply — 1 reply tới trong bất kỳ trạng thái nào ở trên chắc chắn là gửi lại của 1 cái đã xử lý, vì thứ duy nhất có thể tạo ra reply mới lúc đang ở `CONFIRMED` (reply của commit-stock) chỉ được gửi sau khi `startPayment` đã chuyển step qua khỏi đó. Và `SagaStep` cũng khai báo thêm giá trị `COMPENSATING` mà hiện chưa có đoạn code nào gán tới — nó không nằm trong luồng chạy thật, chỉ đang được để dành cho 1 trạng thái compensation đang-chạy-dở nếu sau này cần tới.

Release 1 bàn bị chặn bởi nhiều hơn chỉ status của đơn hàng hiện tại: `POST /api/tables/{id}/release` chỉ thành công khi *mọi* đơn hàng từng gắn với bàn đó đều đã `CANCELLED` hoặc `PAID` — mọi status khác trong bảng trên (`OPEN`, `PENDING_CONFIRMATION`, `CONFIRMED`, `PAYMENT_PENDING`) đều chặn release, vì release giữa chừng sẽ cho phép 1 đơn hàng thứ 2 bắt đầu trên 1 bàn mà đơn hàng trước vẫn còn claim thật sự. Điều kiện check ở release và điều kiện đối xứng mà `OrderService` dùng để chặn đơn hàng thứ 2 trên 1 bàn đã có đơn hàng đang xử lý cùng kiểm tra 1 invariant từ 2 đầu khác nhau của vòng đời 1 bàn, biểu diễn qua cặp set phần-bù-chính-xác `OrderStatus.CLOSED_STATUSES`/`NON_CLOSED_STATUSES` — chỉ định nghĩa 1 lần duy nhất thay vì 2 danh sách duy trì độc lập.

## Luồng xác thực

1. Client đăng nhập qua `POST /api/auth/login` (public, không cần token) — auth-service kiểm tra thông tin đăng nhập và cấp JWT ký bằng RS256.
2. Mọi request sau đó đều mang JWT này qua header `Authorization: Bearer <token>`.
3. `JwtAuthGlobalFilter` ở gateway là nơi duy nhất từng thấy và xác thực JWT: nó xóa bỏ mọi header `X-User-*` mà client tự gửi lên (để không thể giả mạo danh tính), xác thực chữ ký bằng public key của auth-service (lấy qua biến môi trường `APP_JWT_PUBLIC_KEY` — trước đây lấy runtime từ config-server, tới khi bị retired, xem mục [Thành phần đã retired](#thành-phần-đã-retired)), và chỉ khi thành công mới set các header đáng tin cậy `X-User-Id`/`X-Username`/`X-User-Role` dựa trên claim trong token.
4. Các service phía sau không bao giờ thấy JWT; chúng tin tưởng header do gateway set, thông qua `HeaderAuthenticationFilter` trong `common-lib`. Token thiếu hoặc không hợp lệ sẽ bị trả về `401` ngay tại gateway, trước khi tới được bất kỳ service nghiệp vụ nào.

## Các pattern đã áp dụng

Vì mục đích của dự án là luyện tập các pattern kinh điển, nên liệt kê rõ những pattern nào đã được áp dụng tính tới thời điểm hiện tại, nhóm theo vấn đề chúng giải quyết thay vì theo thứ tự implement. Tên pattern theo đúng catalog phổ biến (bộ [microservices.io](https://microservices.io/patterns/index.html) của Chris Richardson bao phủ hết các pattern dưới đây, trừ Circuit Breaker/Retry thuộc Enterprise Integration Patterns, và Optimistic Concurrency Control — 1 pattern kinh điển của transaction-processing, có trước cả microservices) — nên tra định nghĩa gốc trước nếu chưa quen tên, rồi quay lại xem codebase này áp dụng nó thế nào.

### Nền tảng (Platform)

- ~~**Service Discovery** — Eureka (`eureka-server`)~~ **Đã retired 2026-09**, xem mục [Thành phần đã retired](#thành-phần-đã-retired)
- **API Gateway** — Spring Cloud Gateway, cổng vào duy nhất + CORS + routing
- ~~**Externalized Configuration** — Spring Cloud Config Server, profile native được backing bởi `config-repo` bind-mount~~ **Đã retired 2026-09**, xem mục [Thành phần đã retired](#thành-phần-đã-retired)
- **Trusted Header Authentication** — gateway xác thực JWT một lần duy nhất rồi chuyển tiếp danh tính qua header `X-User-Id`/`X-Username`/`X-User-Role`; các service phía sau tin tưởng gateway thay vì tự xác thực lại (`TrustedHeaderAuth` trong `common-lib`)
- **Database per Service** — mỗi service có 1 database Postgres và 1 role riêng

### Khả năng chịu lỗi (Resilience)

- **Circuit Breaker + Retry** — Resilience4j cho lời gọi từ order-service sang menu-service

### Saga đơn hàng & tính nhất quán

- **Orchestrated Saga** — luồng checkout của order-service điều khiển một state machine (`OrderSaga`) gồm 2 chặng: Xác thực (giữ chỗ mềm tồn kho, `OPEN`→`CONFIRMED`) và Thanh toán (commit chỗ đã giữ, `CONFIRMED`→`PAID`), mỗi chặng là 1 vòng round-trip Kafka riêng, tự commit hoặc compensate dựa theo reply nhận được; xem mục "Luồng nghiệp vụ: saga xác thực và thanh toán" bên trên
- **Giữ chỗ tồn kho kiểu Try-Confirm/Cancel (TCC)** — inventory-service không bao giờ trừ thẳng `currentStock` ngay khi checkout. Xác thực là bước *Try* (giữ chỗ vào `reservedQuantity`), thanh toán là bước *Confirm* (biến chỗ giữ thành trừ kho thật), hủy đơn `CONFIRMED` là bước *Cancel* (trả lại chỗ giữ) — đúng 3 bước kinh điển của pattern TCC, đặt chồng lên trên saga ở trên chứ không thay thế nó
- **Optimistic Concurrency Control** — `DiningTableService.occupy()`/`release()` mỗi hàm tự bảo vệ 1 bàn bằng 1 câu lệnh `UPDATE ... WHERE` có điều kiện duy nhất (`DiningTableRepository.occupyIfAvailable`/`releaseIfAllOrdersClosed`) thay vì đọc-rồi-ghi tách rời. `WHERE` của `occupy()` check status của chính bàn đó, nên 2 lần gọi `occupy()` gần như đồng thời trên cùng 1 bàn không thể cùng thành công. `WHERE` của `release()` check (qua subquery) rằng không còn đơn hàng nào gắn với bàn đó ở status non-closed — đóng đúng race giữa việc release và 1 đơn hàng chuyển sang status non-closed ngay giữa chừng, chứ không phải race giữa 2 lần gọi `release()` với nhau (2 lần đó thì cứ thành công vô hại cả 2). Dù cách nào thì cũng là race trên 1 dòng dữ liệu, khác với việc điều phối xuyên service của saga/TCC — 1 rủi ro nhất quán khác, xử lý bằng 1 kỹ thuật khác

### Độ tin cậy khi truyền message (Messaging reliability)

Cả 5 pattern dưới đây đều bảo vệ cùng 1 luồng trao đổi qua Kafka (saga ở trên) trước cùng 2 rủi ro — Kafka gửi lại message (at-least-once) và "phía kia không bao giờ trả lời" — mỗi pattern giải quyết theo 1 cách khác nhau, bổ sung cho nhau:

- **Idempotent Consumer** — đảm bảo xử lý lại 1 message bị gửi trùng là an toàn, mà không làm sai lệch kết quả.
  - Các reply handler trong saga đơn hàng của order-service (`OrderSaga.onStockReservationReply`/`onStockCommitReply`) dùng `OrderSagaStateService.shouldIgnoreReply`: coi `COMPLETED`, `COMPENSATED`, và `CONFIRMED` là terminal cho attempt hiện tại của saga, cộng thêm check `correlationId` đã cũ (thuộc về 1 attempt đã bị 1 attempt mới thay thế).
  - Vì sao `CONFIRMED` cũng được tính là terminal: về cấu trúc nó luôn là trạng thái rảnh "chờ hành động tiếp theo của user" trong state machine này (chỉ đạt được từ verify leg thành công hoặc payment leg thất bại/revert) — không có kịch bản hợp lệ nào mà 1 reply cần được xử lý lúc saga đang ở đó, nên bất kỳ reply nào tới trong trạng thái này chắc chắn là bị gửi lại của 1 cái đã xử lý rồi.
  - Vẫn giữ đồng bộ, khác với Transactional Inbox bên dưới — xử lý reply ở order-service nhanh và không có side-effect nào ngoài cập nhật state của chính nó.
- **Transactional Inbox** — phiên bản đầy đủ, bất đồng bộ của Idempotent Consumer: tách việc *nhận* message khỏi việc *xử lý* nó, thay vì làm cả 2 ngay trong listener thread.
  - 3 method `@KafkaListener` của `StockReservationListener` chỉ lưu command nhận được vào bảng `inbox_messages` (status `PENDING`, khoá là `correlationId`) rồi ACK — không chạy business logic ngay bên trong.
  - Một worker chạy theo lịch riêng, `InboxPoller`, sẽ nhặt 1 batch dòng `PENDING` (`SELECT ... FOR UPDATE SKIP LOCKED`, an toàn khi có nhiều poller chạy đồng thời) và giao từng dòng cho `InboxMessageProcessor` — nơi thực sự chạy bước `reserve`/`commit`/`release` và đánh dấu dòng `PROCESSED` cùng lúc trong 1 transaction, rồi mới publish reply (chỉ reserve/commit — release thì không có reply).
  - Vì sao cần 1 worker bất đồng bộ riêng thay vì chạy thẳng trên listener thread: reserve/commit tồn kho có khóa nhiều dòng ingredient cùng lúc và validate nhiều bước, không đủ an toàn hay đủ nhanh để chạy đồng bộ ngay trên consumer thread của Kafka — Transactional Outbox bên dưới cũng tách làm 2 phần tương tự (ghi bền vững, rồi 1 relay riêng), nhưng phần relay của nó nhẹ hơn nhiều (chỉ gửi lại payload đã lưu, không có business logic) — nên khác biệt ở đây nằm ở lượng việc làm *sau* bước ghi bền vững, chứ không phải có hay không có bước tách đó.
  - `correlationId` vẫn là khoá khử trùng lặp: 1 command bị gửi lại mà dòng tương ứng đã `PROCESSED` sẽ được gửi lại đúng reply đã lưu mà không chạy lại business logic (cần thiết để retry cùng correlationId của `OrderSagaReconciliationJob` vẫn được trả lời); còn dòng vẫn `PENDING`/`PROCESSING`/`FAILED` thì bị bỏ qua.
  - Lỗi kỹ thuật khiến transaction của lần thử đó rollback; dòng được đưa lại `PENDING` để thử tiếp (tới `app.inbox.max-attempts` lần), hoặc khi hết lượt thì chuyển `FAILED` vĩnh viễn — im lặng, có chủ đích (xem Reconciliation bên dưới để biết vì sao im lặng vẫn an toàn).
- **Transactional Outbox** — đối xứng phía gửi của Transactional Inbox ở trên: biến "commit 1 thay đổi trạng thái" và "đảm bảo bền vững message phải theo sau nó" thành 1 hành động atomic, bằng cách ghi cả 2 vào cùng database trong cùng 1 transaction, thay vì commit thay đổi trạng thái rồi mới gọi Kafka trực tiếp ở 1 bước riêng.
  - `OrderSaga` của order-service ghi 1 dòng `OutboxMessage` (status `PENDING`) trong *cùng* transaction với mọi thay đổi order/saga-state cần 1 Kafka message theo sau nó — reserve, commit, release, và event `order.paid` cuối cùng. Trước khi có pattern này, đây là 2 transaction tách biệt (commit cục bộ, rồi gọi `KafkaTemplate.send()` trực tiếp); nếu crash ở giữa, saga có thể kẹt lại mà không có command nào từng được gửi, và `OrderSagaReconciliationJob` không phát hiện ra (nó chỉ quét các step do 1 command *đã gửi* tạo ra, không quét step `STARTED` trước khi gửi). `InboxMessageProcessor` của inventory-service cũng có cấu trúc y hệt cho 2 topic reply của nó, enqueue reply vào cùng transaction với thay đổi tồn kho + cập nhật status inbox mà nó đang trả lời.
  - Một `OutboxPoller` chạy theo lịch riêng, 1 cái cho mỗi service, nhặt 1 batch dòng `PENDING` theo đúng kiểu `SELECT ... FOR UPDATE SKIP LOCKED` mà `InboxPoller` dùng, rồi giao từng dòng cho `OutboxMessagePublisher` — nơi gửi message đó và chờ (block) trên future gửi Kafka (`app.outbox.publish-timeout`) để dòng chỉ chuyển sang `PUBLISHED` khi broker đã thực sự ack — làm ít hơn thế sẽ mở lại đúng lỗ hổng dual-write mà pattern này sinh ra để đóng lại.
  - Cùng kiểu retry/bỏ cuộc như Transactional Inbox: gửi thất bại thì quay lại `PENDING` để thử ở lượt quét sau (tới `app.outbox.max-attempts` lần), rồi mới `FAILED` vĩnh viễn. Dòng bị kẹt ở `PROCESSING` vì process crash sau khi broker đã ack nhưng trước khi commit là 1 khoảng trống được biết trước và chấp nhận, không được thu hồi lại — cùng đánh đổi mà `InboxPoller` đã chấp nhận ở phía nó.
- **Reconciliation** — `OrderSagaReconciliationJob` quét các saga bị kẹt khi chờ reply ở **cả 2 chặng**, rồi retry hoặc compensate về đúng trạng thái đích tương ứng từng chặng (xem luồng nghiệp vụ bên trên). Đây là lưới an toàn cho tình huống "không có reply nào tới" — Idempotent Consumer và Transactional Inbox chỉ xử lý trường hợp reply *có* tới, dù đúng hẹn hay bị gửi lại.
- **Dead Letter Queue** — inventory-service chuyển các message lỗi vì nguyên nhân *kỹ thuật* ở tầng nhận message từ Kafka (payload sai định dạng, bug, lỗi DB — không bao giờ tính trường hợp nghiệp vụ "hết hàng", vì đó là 1 reply bình thường, không phải exception) sang topic `.dlq` sau vài lần retry theo exponential backoff, thay vì để nó chặn cứng consumer (poison-pill message). Áp dụng đồng loạt cho cả 3 topic command của inventory (`reserve-stock`, `commit-stock`, `release-stock`) qua 1 bean xử lý lỗi dùng chung, không cấu hình riêng từng topic

### Khả năng quan sát (Observability)

- **Truy vết phân tán (Distributed Tracing)** — mọi service đều export span sang Zipkin (`http://localhost:9411`) qua Micrometer Tracing + Brave; HTTP (routing ở gateway, các lời gọi WebClient) và Kafka produce/consume được tự động instrument (`spring.kafka.template`/`listener.observation-enabled`), nên `traceId` của 1 request sống sót qua mọi hop mạng mà không cần code thêm gì.
  - Có 1 khoảng mà auto-instrumentation không tự nối được: các thread relay bất đồng bộ của saga đơn hàng (`OutboxPoller`→`OutboxMessagePublisher`, `InboxPoller`→`InboxMessageProcessor`) chạy tách rời khỏi thread Kafka consumer đã nhận message kích hoạt, nên không có span nào đang sống để kế thừa ở đó. `OutboxMessage`/`InboxMessage` có thêm cột `traceparent` (định dạng W3C): phía *enqueue* (`OrderSaga.enqueue`, `StockReservationListener.enqueue`, `InboxMessageProcessor.enqueueReply`) chụp lại span đang active vào cột đó lúc ghi, còn phía *relay* (`OutboxMessagePublisher.publishOne`, `InboxMessageProcessor.processOne`) khôi phục nó thành 1 span con mới trước khi làm việc — khâu lại khoảng trống bất đồng bộ vào cùng 1 trace thay vì tạo ra 1 trace rời rạc mới.
  - 1 dòng không có traceparent lưu sẵn (không có span nào đang sống lúc enqueue — ví dụ vòng sweep định kỳ của `OrderSagaReconciliationJob` khi re-queue 1 saga bị kẹt) sẽ rơi về khởi tạo 1 span gốc mới thay vì lỗi; mỗi lần retry của reconciliation là 1 trace hoàn chỉnh, độc lập riêng, chứ không phải 1 liên kết gãy trong trace gốc.
  - Health-check polling của Docker (`GET /actuator/health`, gọi mỗi vài giây/container) bị loại khỏi tracing ở mọi service. Vòng sweep định kỳ của `OrderSagaReconciliationJob` bên order-service cũng bị loại tương tự, qua 1 bean `ObservationPredicate` thay vì lọc theo *tên* observation — mọi method `@Scheduled` dùng chung 1 tên `tasks.scheduled.execution` (giống hệt cách mọi HTTP request dùng chung `http.server.requests`), nên lọc theo tên sẽ âm thầm tắt tracing của mọi scheduled method khác, không chỉ riêng cái này. Predicate scheduled-poller (`ScheduledPollerObservationPredicates`, package-private trong package `config` riêng của order-service) thay vào đó match theo target class của observation — chỉ được điền cho các task Spring bọc qua cơ chế `@Scheduled` (`ScheduledMethodRunnable`). Các poller outbox/inbox (`OutboxPoller` của order-service và inventory-service, `InboxPoller` của inventory-service) không cần predicate này và cũng không nằm trong đó: chúng đăng ký fixed delay qua `SchedulingConfigurer`/`ScheduledTaskRegistrar.addFixedDelayTask` thay vì `@Scheduled`, để mỗi cái lấy interval từ 1 giá trị `@ConfigurationProperties` đã bind thay vì 1 placeholder mặc định riêng dễ lệch. Đường đăng ký đó cũng không bao giờ tạo ra observation `tasks.scheduled.execution` nào cả, nên chẳng có gì để lọc cho chúng.
  - Loại trừ health-check theo path phức tạp hơn nhìn bề ngoài: predicate chạy *trước khi* request được dispatch tới handler, nên `Observation.Context.getPathPattern()` — route đã resolve — chưa được set tại thời điểm đó, luôn là `null`. gateway (không chạy Spring Security filter chain) tự giữ 1 predicate riêng, né vấn đề này bằng cách match trên request thô thay vì path pattern, qua `context.getCarrier()` — có sẵn ngay lập tức, không như pattern đã resolve; bản của gateway dùng context reactive riêng biệt nên thực sự không thể dùng chung với predicate servlet-based mà 5 service kia dùng. (Tới trước khi retired 2026-09, `config-server` và `eureka-server` cũng từng mỗi bên giữ 1 bản giống hệt cách né này, cố tình không gom chung — xem mục [Thành phần đã retired](#thành-phần-đã-retired).)
  - Ở 5 service có chạy Spring Security filter chain (auth, menu, order, inventory, report), chỉ match theo path là chưa đủ: các observation riêng của Spring Security (filter chain, authorization) thuộc 1 loại `Observation.Context` khác hẳn, hoàn toàn không có field path/URI nào — nên không predicate nào có thể nhận diện chúng chỉ bằng cách đọc context. Thay vào đó, `HealthCheckMarkingFilter` (`common-lib`, đăng ký chạy trước mọi filter khác từng tạo observation) đánh dấu thread hiện tại khi request nhắm tới `/actuator/health`; `HealthCheckObservationPredicates.excludingMarkedRequests()` sau đó loại trừ mọi observation được tạo ra trên thread đã đánh dấu, bất kể loại context nào — cả tầng HTTP lẫn tầng Spring Security — trong khi request thật (thread không bao giờ bị đánh dấu) vẫn giữ nguyên độ sâu tracing.

## Thành phần đã retired

- **Service Discovery (Eureka)** và **Externalized Configuration (Spring Cloud Config Server)** — cả 2 đều đã retired 2026-09, như bước đầu tiên của việc chuyển deploy target từ `docker-compose` sang Kubernetes (xem [Cafe Roadmap](https://claude.ai/code/artifact/45eea53a-1a1a-4dfe-88bc-f1a1fae63a07?org=ab443343-5dd7-4698-b7cc-00e521059318) để biết quá trình migration đang diễn ra). Kubernetes tự cung cấp cả 2 nhu cầu này — Service DNS cho discovery, ConfigMap/Secret cho config — nên Eureka/`eureka-server` và Spring Cloud Config/`config-server` ở tầng ứng dụng bị xóa hẳn thay vì port sang.
- Hệ quả cụ thể: mọi lời gọi giữa các service (bảng route của gateway, lời gọi từ `order-service` sang `menu-service`) giờ trỏ thẳng tới `host:port` cố định thay vì tên logic phân giải qua Eureka; config vận hành của mỗi service (trước đây lấy runtime từ `config-repo` của `config-server`) giờ nằm sẵn trong `application.yml` của chính service đó — trừ secret (username/mật khẩu DB, JWT key), những giá trị này lấy qua biến môi trường thay vì viết vào `application.yml`: khi chạy local là file `.env` mà `docker-compose.yml` đọc qua cơ chế thay thế biến (xem `.env.example`); khi deploy thật là K8s Secret đồng bộ từ GCP Secret Manager.
- Ảnh hưởng tới local dev: `docker compose up` không còn khởi động container `eureka-server`/`config-server` nữa — ít hơn 1 phần phải chạy, không phải regression. DNS nội bộ của Docker Compose vẫn phân giải đúng tên service cố định (vd. `http://menu-service:8082`) cho bất kỳ *container khác* trên cùng network như trước; thứ duy nhất trước đây tự động có sẵn nhờ Eureka mà giờ cần thêm 1 bước thủ công 1 lần là gọi service theo tên từ 1 tiến trình chạy **bare** (vd. từ IDE) cùng lúc với phần còn lại chạy Docker — xem mục [Xử lý sự cố thường gặp](#xử-lý-sự-cố-thường-gặp) bên dưới.

## Cấu trúc

```
backend/    Maven multi-module reactor: 5 domain services + gateway + common-lib
frontend/   Angular (standalone components)
docker/     Script khởi tạo Postgres
charts/     Helm chart cho deploy thật lên GKE: cafe-service (chart tái sử dụng cho từng service)
            + cafe (umbrella chart alias nó 6 lần, mỗi service 1 alias)
k8s/        Manifest K8s/CNPG/Strimzi thuần cho tầng dữ liệu (Postgres cluster +
            storage class + backup, Kafka cluster) và các file values override Helm
            cho operator dùng chung toàn cluster (hiện chỉ có của Strimzi)
scripts/    Script dùng chung giữa máy local và CI, vd. image-tag.sh (tính tag
            content-hash cho image của 1 backend service)
.github/    Workflow GitHub Actions (hiện có: backend-ci.yml, xem mục Kiểm thử bên dưới
            và Bước 9 của docs/gke-cicd-runbook.md)
docs/       Tài liệu hướng dẫn từng bước (hiện có: quá trình build GKE/CI-CD, xem
            docs/gke-cicd-runbook.md)
```

(Tới trước 2026-09, `backend/` còn có thêm module `eureka-server` và `config-server` — đã retired, xem mục [Thành phần đã retired](#thành-phần-đã-retired).)

Tới trước khi config-server bị retired, config native của nó nằm ở `backend/config-server/src/main/resources/config-repo/`, bind-mount dạng read-only vào container `config-server` nên sửa 1 file `config-repo/*.yml` chỉ cần restart, không cần rebuild image. Giờ config vận hành của mỗi service nằm thẳng trong `src/main/resources/application.yml` của chính service đó — muốn đổi thì phải rebuild lại image của service đó. Riêng secret là ngoại lệ: chúng không nằm trong `application.yml`, lấy qua biến môi trường thay thế (xem mục [Thành phần đã retired](#thành-phần-đã-retired) để biết lấy từ đâu) — nên đổi được mà không cần rebuild.

## Yêu cầu môi trường

- Java 21
- Node.js 20+ (Angular 21 / npm 11)
- Docker & Docker Compose
- Helm & kubectl, và quyền truy cập 1 Kubernetes cluster — chỉ cần cho việc deploy `charts/`/`k8s/`
  lên GKE, không cần khi chạy local qua Docker Compose bên dưới

## Chạy ở local

```bash
cp .env.example .env   # chỉ cần làm 1 lần — cấp DB credentials + JWT key cho docker compose
docker compose up -d
cd frontend && ng serve
```

`docker compose up -d` khởi động toàn bộ phần backend cùng lúc — Postgres, Kafka, Kafka UI, Zipkin, gateway, và cả 5 domain service (`eureka-server`/`config-server` không còn nằm trong stack nữa, xem mục [Thành phần đã retired](#thành-phần-đã-retired)) — sau đó chạy dev server frontend riêng, ngoài Compose, có hot reload. Các lệnh dùng hàng ngày ngoài lệnh khởi động ban đầu:

```bash
docker compose ps                           # xem container nào đang chạy, trạng thái health
docker compose logs -f order-service        # xem log 1 service theo thời gian thực (Ctrl+C để dừng)
docker compose up -d --build                # build lại + restart tất cả service (mvn package sẽ chạy test trước — xem mục Kiểm thử bên dưới)
docker compose up -d --build order-service  # build lại + restart 1 service sau khi sửa code (mvn package sẽ chạy test trước — xem mục Kiểm thử bên dưới)
docker compose restart order-service        # restart mà không rebuild, vd sau khi đổi 1 env var trong docker-compose.yml, hoặc chỉ để khởi động lại container đang treo
docker compose down                         # dừng và xoá toàn bộ container; volume Postgres (postgres-data) vẫn giữ nguyên
docker compose down -v                      # như trên, nhưng xoá luôn data Postgres — dùng khi muốn làm sạch hoàn toàn
```

Gateway (cổng vào duy nhất cho frontend): http://localhost:8080
Kafka UI: http://localhost:8090

Không có flow tự đăng ký — tài khoản nhân viên chỉ được tạo bởi ADMIN. Ở lần khởi động đầu tiên, auth-service tự động seed 1 tài khoản admin mặc định (`admin` / `admin123`) nếu bảng `users` đang rỗng, để có tài khoản đăng nhập ban đầu. Tài khoản này chỉ dùng cho dev; khi triển khai thật cần seed tài khoản admin đầu tiên theo cách khác (out-of-band). Có 2 role: `ADMIN` và `CASHIER`.

## Kiểm thử (Testing)

Unit test frontend chạy trên bộ test builder của Angular (nền tảng Vitest):

```bash
cd frontend
npm test               # chế độ watch
npm run test:coverage  # chạy 1 lần, kèm báo cáo coverage dạng HTML
```

`test:coverage` ghi ra báo cáo drill-down tại `frontend/coverage/frontend/index.html` — mở bằng trình duyệt để xem coverage theo từng thư mục, rồi từng file, rồi từng dòng code (thư mục/file có thể bấm vào, dòng chưa được test sẽ tô đỏ). Quy ước của dự án: mọi component có code mới hoặc sửa đổi đều cần unit test đạt tối thiểu 70% coverage trước khi coi là hoàn thành.

Unit test backend chạy theo từng module bằng Maven (JUnit 5 + Mockito):

```bash
cd backend
mvn -pl inventory-service -am test
```

Một số module là ngoại lệ: 1 số class test chạy trên Postgres và/hoặc Kafka thật qua [Testcontainers](https://testcontainers.com/) thay vì mock, để thực sự kiểm chứng các hành vi mà mock không thể kiểm tra được — với nhóm dùng Postgres, đó là 1 khoá thật giúp serialize các transaction chạy đồng thời, trạng thái persistence-context thật của Hibernate, hay JPQL/SQL tự viết; với nhóm dùng Kafka, đó là việc kết nối tới broker thật (deserialize JSON, đọc header, dispatch theo topic). order-service và inventory-service hiện đều có loại test dùng Postgres thật, và riêng order-service có thêm loại dùng Kafka thật, nên `mvn -pl <module> -am test` với module nào trong 2 module đó cũng cần Docker daemon đang chạy; mỗi class như vậy được gắn `@Tag("testcontainers")` để có thể loại trừ (`-DexcludedGroups=testcontainers`) ở những nơi không có Docker, ví dụ bước build Docker image của chính service đó. Docker build của mọi service đều chạy unit test như 1 phần của `mvn package` (không có `-DskipTests` ở đâu cả), nên `docker compose build <service>` vừa build vừa đóng vai trò 1 cổng kiểm thử, không chỉ đơn thuần là đóng gói.

Module nào bật `jacoco-maven-plugin` (khai báo 1 lần ở `pluginManagement` của `pom.xml` gốc; hiện `common-lib`, `auth-service`, `menu-service`, `order-service`, và `inventory-service` đã kích hoạt) sẽ ghi ra báo cáo coverage dạng HTML drill-down sau mỗi lần `mvn test`, tại `<module>/target/site/jacoco/index.html` — ví dụ `backend/inventory-service/target/site/jacoco/index.html`. Đây chỉ là file tĩnh, không có server nào phục vụ cả: mở dạng URL `file://`, ví dụ `file:///<đường-dẫn-repo>/backend/inventory-service/target/site/jacoco/index.html` (thay bằng đường dẫn tuyệt đối repo của bạn), hoặc double-click file đó cũng được. Bạn sẽ thấy coverage theo từng package, rồi từng class, rồi từng dòng code (cùng kiểu drill-down như báo cáo bên frontend; dòng chưa được test sẽ tô đỏ). Muốn xem module khác khi module đó bật jacoco, chỉ cần đổi tên module ở `-pl` và đường dẫn tương ứng. Mỗi module đã opt-in tự đặt property `jacoco.line.coverage.minimum` riêng — 1 ratchet không cho phép thụt lùi, khớp đúng coverage hiện tại của module đó, hoặc mặc định 70% của pom cha cho module đã đạt hoặc vượt mức đó — được `mvn jacoco:check` enforce; coverage backend đang được nâng dần từng module một chứ chưa phủ hết cùng lúc, nên xem trực tiếp codebase để biết sàn coverage hiện tại của từng module thay vì coi README này là nơi theo dõi.

[`.github/workflows/backend-ci.yml`](.github/workflows/backend-ci.yml) quét secret bằng `gitleaks` ở mọi lần push và pull request. Job `test` của nó — chỉ chạy khi `backend/**` hoặc `scripts/**` có thay đổi (hoặc khi chạy `workflow_dispatch` thủ công) — chạy thêm `spotless:check`, toàn bộ reactor `mvn test`, `mvn jacoco:check` với các sàn coverage theo từng module ở trên, và `shellcheck`/tự kiểm `scripts/image-tag.sh`. Khi push lên `master`, nó còn build và push image của từng service lên Artifact Registry, gắn tag theo content hash (xem [`scripts/image-tag.sh`](scripts/image-tag.sh)) — xem Bước 9 của `docs/gke-cicd-runbook.md` để biết toàn bộ pipeline, cách gating theo path, và phần cấu hình GCP 1 lần mà nó cần.

## Định dạng code (Code formatting)

Backend dùng [Spotless](https://github.com/diffplug/spotless) với Google Java Format, khai báo 1 lần (dạng plugin chủ động, không chỉ `pluginManagement`) ở `backend/pom.xml` gốc — mọi module con tự động kế thừa, không cần opt-in riêng từng module:

```bash
cd backend
mvn spotless:check   # báo lỗi nếu file đã sửa chưa đúng định dạng
mvn spotless:apply   # tự viết lại file cho đúng định dạng
```

Frontend dùng [Prettier](https://prettier.io/), cấu hình tại `frontend/.prettierrc`:

```bash
cd frontend
npm run format:check
npm run format
```

Cả 2 được tự động enforce qua git hook `pre-commit` (`.git/hooks/pre-commit` — không được git track vì hook nằm ngoài version control; cần copy thủ công khi clone máy mới) — chặn commit nếu code đã stage chưa đúng định dạng. Cấu hình `ratchetFrom` của Spotless nghĩa là chỉ những file khác biệt so với `origin/master` mới bị kiểm tra — code cũ giữ nguyên định dạng ban đầu cho tới khi có ai đó động vào lại, không có 1 commit "format lại toàn bộ" nào phải lướt qua.

## Xử lý sự cố thường gặp

- *(Lịch sử — chỉ áp dụng khi project còn dùng Eureka, đã retired 2026-09, xem mục [Thành phần đã retired](#thành-phần-đã-retired))* **Gateway từng trả về 503 ngay sau khi restart 1 service** — load balancer của Spring Cloud Gateway giữ cache instance của service (phân giải qua Eureka) trong thời gian ngắn; cache này có thể bị stale vài giây sau khi restart. Giờ gateway route thẳng tới `host:port` cố định của từng service, nên loại lỗi này không còn xảy ra được nữa.
- **Docker build cache chiếm hết dung lượng ổ đĩa** — build đi build lại nhiều lần (`docker compose build`) trong lúc dev để lại các layer image cũ, không tự dọn. Chạy `docker builder prune -f` định kỳ để giải phóng dung lượng, hoặc `docker system df` để xem cái gì đang chiếm chỗ.
- **1 test dùng Testcontainers (các class gắn tag testcontainers của order-service hoặc inventory-service, v.v.) không kết nối được, báo lỗi timezone** — driver JDBC của Postgres yêu cầu server `SET TIME ZONE` theo múi giờ mặc định của JVM khi kết nối; trên máy mà OS báo 1 tên alias IANA cũ (vd `Asia/Saigon`, đã được thay bằng `Asia/Ho_Chi_Minh`), tzdata đóng gói sẵn trong image Testcontainers `postgres:16` không nhận ra tên đó và từ chối kết nối luôn. `pom.xml` của cả order-service lẫn inventory-service đều ép `-Duser.timezone=UTC` cho riêng `maven-surefire-plugin` của mình để khỏi phải phụ thuộc vào việc tên timezone cấp OS của từng máy dev có được đúng image Postgres này chấp nhận hay không.
- **Sửa nội dung 1 file migration đã được apply rồi, giờ startup báo lỗi checksum mismatch của Flyway** — `validate-on-migrate` mặc định bật (project này không override) và tính checksum nội dung từng file migration ngay lần chạy đầu tiên, rồi so lại checksum đó ở mỗi lần startup sau. Sửa nội dung 1 file đã apply — kể cả chỉ sửa comment — cũng làm đổi checksum và fail validate so với checksum Postgres đã lưu. Nếu có lúc cần sửa 1 migration đã apply, đừng xóa database để né lỗi này — tính lại checksum đã lưu bằng thao tác `repair` riêng của Flyway. Không có `pom.xml` nào ở đây khai báo `flyway-maven-plugin`, nên phải gọi bằng tọa độ đầy đủ, chạy từ thư mục module của service đó:
  ```bash
  mvn org.flywaydb:flyway-maven-plugin:12.4.0:repair -Dflyway.url=jdbc:postgresql://localhost:5432/<db> -Dflyway.user=<user> -Dflyway.password=<password> -Dflyway.locations=filesystem:src/main/resources/db/migration
  ```
- *(Lịch sử — chỉ áp dụng khi project còn dùng Eureka, đã retired 2026-09, xem mục [Thành phần đã retired](#thành-phần-đã-retired))* **1 service từng không gọi được service khác (Eureka lookup treo hoặc trả 500) khi chạy 1 service bare từ IDE cùng lúc với phần còn lại đang chạy Docker** — `eureka.instance.hostname` của mỗi service trước đây mặc định là `host.docker.internal` thay vì IP tự nhận diện, vì trên Windows, IP tự nhận diện đó có thể rơi vào 1 virtual adapter (VPN/WSL/Hyper-V) mà container Docker không route tới được. Toàn bộ nhóm lỗi này (kể cả việc Docker Desktop định kỳ ghi đè dòng `host.docker.internal` trong hosts file) không còn xảy ra nữa vì routing giờ không còn đi qua Eureka — xem mục ngay bên dưới để biết cách thay thế.
- **1 service không gọi được service khác (connection refused / không tìm thấy host) khi chạy 1 service bare từ IDE cùng lúc với phần còn lại đang chạy Docker** — route giờ là hostname cố định (vd. `http://menu-service:8082`) thay vì phân giải qua Eureka. DNS nội bộ của Docker Compose tự phân giải đúng những tên đó cho bất kỳ *container khác* trên cùng network, nhưng tiến trình chạy **bare-host** (vd order-service chạy từ Eclipse) không nằm trên network đó nên hoàn toàn không resolve được tên service trần. Vì Compose đã publish sẵn port của mọi service ra host (`ports:` trong `docker-compose.yml`), cách sửa là thêm 1 lần duy nhất alias hosts file, trỏ tên từng service về `127.0.0.1`, chạy với quyền Administrator:
  ```powershell
  Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "127.0.0.1 auth-service menu-service order-service inventory-service report-service"
  ```
  Sau đó, service chạy bare sẽ resolve tên bất kỳ service nào khác về `127.0.0.1:<port đã publish>`, y hệt như đang gọi 1 container khác.
- **1 service không khởi động được khi chạy trần từ IDE** — username/mật khẩu DB và JWT key để trống trong `application.yml`, lấy qua biến môi trường thay thế (xem mục [Thành phần đã retired](#thành-phần-đã-retired) để biết lấy từ đâu) — chạy trần từ IDE không có các biến này. Với auth-service/gateway, lỗi hiện ra dạng `IllegalArgumentException: "Invalid RSA private key PEM"` / `"Invalid RSA public key PEM"` (parse chuỗi PEM rỗng); với 4 service chỉ dùng DB, lỗi hiện ra dạng `PSQLException: "The server requested SCRAM-based authentication, but no password was provided."`, bọc trong `BeanCreationException`. Cách sửa: `cp application-local.yml.example application-local.yml` trong thư mục module của service đó, rồi bật profile `local` của Spring (VM option `-Dspring.profiles.active=local`, hoặc biến môi trường `SPRING_PROFILES_ACTIVE=local`) trong cấu hình run của IDE. `application-local.yml` đã gitignore và bị loại khỏi jar/image build ra (xem `backend/pom.xml`) — việc cấu hình IDE để nhận file này (vd tắt "delegate build/run to Maven" nếu setting đó khiến IDE bỏ qua resource không qua Maven xử lý) là trách nhiệm của từng dev.

</details>
