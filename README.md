# Reconcile — backend

API Kotlin/Spring Boot da reconciliação de marketplace. Postgres 16 + Flyway + JWT.

## Configuração

```bat
copy .env.example .env
```

Defina um `JWT_SECRET` com pelo menos 64 caracteres aleatórios e um `DATABASE_PASSWORD` próprio. O Compose recusa subir sem os dois.

Em deploy público, use `SEED_DEMO=false` (apaga as contas piloto) e preencha `TRUSTED_PROXIES` com o IP do reverse proxy. Sem isso o limitador de login usa só o IP TCP e ignora `X-Real-IP` / `X-Forwarded-For`.

## Docker

```bat
docker compose up --build
```

API em `http://127.0.0.1:8080`. Usuário demo: `ana.dantas@lojaexemplo.com.br`.

## Testes

```bat
gradlew.bat test
```
