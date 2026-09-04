FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY common-lib/pom.xml common-lib/pom.xml
COPY config-server/pom.xml config-server/pom.xml
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY menu-service/pom.xml menu-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY report-service/pom.xml report-service/pom.xml
RUN mvn -q -N install
COPY common-lib common-lib
COPY order-service order-service
# -DexcludedGroups=testcontainers: tests tagged @Tag("testcontainers") need a real Docker daemon
# (to start a Postgres container) that this build stage has no access to, so they can never pass
# here regardless of whether the test itself is correct - they're tagged specifically so they can
# be excluded here, leaving every other (Docker-independent) test still running as part of this
# build. The real gate for those tagged tests is `mvn test` run directly on the host (or CI, once
# that exists) where Docker is reachable. Note this property is reactor-wide (the -am build also
# includes common-lib), not scoped to order-service alone - harmless today since common-lib has no
# testcontainers-tagged test, but worth remembering if one is ever added there.
RUN mvn -q -pl order-service -am package -DexcludedGroups=testcontainers

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/order-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
