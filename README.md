# Reconcile — backend

[Português](README.pt-BR.md)

Kotlin and Spring Boot API for marketplace reconciliation. Postgres 16, Flyway, and JWT.

**Author:** Filipe de Brito Thomé

## Languages

Error messages follow the `Accept-Language` header: `pt-BR` by default and `en-US` when that header asks for English. The React interface sends the language selected in the UI.

## Setup

```bat
copy .env.example .env
```

Set `JWT_SECRET` to at least 64 random characters and set your own `DATABASE_PASSWORD`. Compose will not start without both.

Local pilot: `SEED_DEMO=true` and `SEED_LEDGER=true` loads accounts and May 2026 orders. `SEED_LEDGER=false` leaves the ledger empty for a real OAuth connection; restoring the seed requires `docker compose down -v`. On a public deploy, set `SEED_DEMO=false` and set `TRUSTED_PROXIES` to the reverse-proxy IP. Without that, the login limiter uses only the TCP address and ignores `X-Real-IP` / `X-Forwarded-For`.

## Docker

```bat
docker compose up --build
```

The API listens on `http://127.0.0.1:8080`. Pilot login: `ana.dantas@lojaexemplo.com.br` / `senha123` (admin).

## Tests

```bat
gradlew.bat test
```

## Copyright

Copyright (c) 2026 Filipe de Brito Thomé. All rights reserved.
