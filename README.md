# Reconcile — backend

API Kotlin/Spring Boot da reconciliação de marketplace. Postgres 16 + Flyway + JWT.

## Configuração

```bat
copy .env.example .env
```

Defina um `JWT_SECRET` com pelo menos 32 caracteres aleatórios. O Compose recusa subir sem isso.

## Docker

```bat
docker compose up --build
```

API em `http://127.0.0.1:8080`. Usuário demo: `ana.dantas@lojaexemplo.com.br`.

## Testes

```bat
gradlew.bat test
```
