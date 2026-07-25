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
COPY config-server config-server
RUN mvn -q -pl config-server -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/config-server/target/*.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
