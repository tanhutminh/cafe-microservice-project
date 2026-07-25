# Cafe Management System

Web app quản lý quán cà phê — kiến trúc microservices (Spring Boot + Angular + PostgreSQL + Kafka).

Xem thiết kế đầy đủ tại kế hoạch triển khai (domain model, service boundaries, saga checkout, routing, docker-compose).

## Cấu trúc

```
backend/    Maven multi-module reactor: 5 domain service + gateway + eureka-server + config-server + common-lib
frontend/   Angular (standalone components)
docker/     Postgres init scripts
```

Cấu hình native cho config-server nằm tại `backend/config-server/src/main/resources/config-repo/` (đóng gói trong chính jar của config-server thay vì thư mục rời ở repo root, để không phụ thuộc đường dẫn tương đối/volume mount khi chạy trong Docker).

## Chạy local dev

```bash
docker-compose up -d
cd frontend && ng serve
```

Gateway (điểm vào duy nhất cho frontend): http://localhost:8080
Eureka dashboard: http://localhost:8761
