# Cafe Management System

Cafe management web app — microservices architecture (Spring Boot + Angular + PostgreSQL + Kafka).

See the implementation plan for the full design (domain model, service boundaries, checkout saga, routing, docker-compose).

## Structure

```
backend/    Maven multi-module reactor: 5 domain services + gateway + eureka-server + config-server + common-lib
frontend/   Angular (standalone components)
docker/     Postgres init scripts
```

config-server's native config lives at `backend/config-server/src/main/resources/config-repo/` (bundled inside config-server's own jar rather than a separate root-level folder, so it doesn't depend on relative paths/volume mounts when running in Docker).

## Running locally

```bash
docker-compose up -d
cd frontend && ng serve
```

Gateway (the single entry point for the frontend): http://localhost:8080
Eureka dashboard: http://localhost:8761
