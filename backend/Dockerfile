# One image recipe for every backend service; SERVICE names the module directory to build, e.g.
#   docker build --build-arg SERVICE=order-service -t cafe-order-service backend
# The build context is backend/, so the parent pom and every module are in reach.

# Dependency cache: only the poms are copied, the parent's and one for each <module> it lists, so
# this stage's go-offline layer is rebuilt only when a pom, the Maven image or this stage itself
# changes; a code change reuses it and reruns the build stage below.
# go-offline fetches the reactor's third-party dependencies and plugins, but not the compiler's
# annotationProcessorPaths (mapstruct-processor, lombok-mapstruct-binding), which the build stage
# downloads each time it runs, nor the reactor's own modules (common-lib), which it builds itself.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY gateway/pom.xml gateway/
COPY auth-service/pom.xml auth-service/
COPY menu-service/pom.xml menu-service/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY report-service/pom.xml report-service/
RUN mvn -B -q dependency:go-offline

# Builds one service: -am also builds what it depends on, the parent pom and common-lib.
# Packages only: -Dmaven.test.skip=true neither compiles nor runs the tests (.dockerignore keeps
# src/test out of the context). They run once, in the `test` job of
# .github/workflows/backend-ci.yml, which must pass before CI builds this image; a local build
# runs none, so run `mvn test` in backend/ first. The jar is then split into Spring Boot's layers,
# from least to most often changed.
FROM deps AS build
ARG SERVICE
# Gives a clear message on a missing SERVICE, but only once the deps stage above is done (normally
# a cache hit); a misspelt one fails at the COPY below instead, whose source BuildKit checks
# before this step has run ("/<name>": not found).
RUN test -n "$SERVICE" && test -d "$SERVICE" \
 || { echo "SERVICE must name a module directory under backend/, e.g. order-service" >&2; exit 1; }
COPY common-lib common-lib
COPY ${SERVICE} ${SERVICE}
RUN mvn -B -q -pl "$SERVICE" -am -Dmaven.test.skip=true package \
 && cp "$SERVICE"/target/*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination /extracted

# Runtime: a JRE only, no JDK or Maven.
FROM eclipse-temurin:21-jre-alpine
# A fixed, numeric non-root user: Kubernetes' runAsNonRoot can only verify a numeric UID.
RUN addgroup -S -g 10001 app \
 && adduser -S -D -H -u 10001 -G app -h /app -s /sbin/nologin app
WORKDIR /app
# The layers in layers.idx order, one COPY each, so a rebuild reuses every layer before the first
# one that changed: a code change, common-lib's included, only touches application, the last.
# spring-boot-loader stays empty without extract's --launcher; it is copied anyway, as in Spring
# Boot's reference Dockerfile. The files stay owned by root, so the app can read its own code but
# not change it.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
# The heap is a share of the container's memory limit (the chart's resources.limits.memory, or
# compose's mem_limit) rather than a fixed -Xmx, so it follows that limit; 50% leaves the rest for
# the JVM's non-heap memory, such as metaspace, the code cache, thread stacks and direct buffers.
#
# ExitOnOutOfMemoryError ends the JVM with exit code 3 at the first OutOfMemoryError for the Java
# heap or metaspace, e.g. "Java heap space" or "Requested array size exceeds VM limit"; others,
# such as for direct buffers or "unable to create native thread", are thrown as usual. The
# container then stops and Kubernetes restarts it rather than leaving a half-broken service up.
# It exits at once and runs no shutdown hooks: there is no graceful shutdown, just as in a crash.
#
# JDK_JAVA_OPTIONS is read only by the java launcher and can be overridden at run time (a new value
# replaces both flags), which leaves JAVA_TOOL_OPTIONS free for local additions such as a debugger
# agent; the JVM reads that one first, so it cannot override these flags.
#
# At every start the launcher prints a "NOTE: Picked up JDK_JAVA_OPTIONS" line to stderr (twice
# here: on Alpine it re-executes itself once), which GKE logs at ERROR severity like any non-JSON
# stderr line; a flag in the ENTRYPOINT would print nothing, but setting JDK_JAVA_OPTIONS could
# then no longer override it: the launcher puts its flags before the command line's own, and of
# two settings of one flag the later wins.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"
# No EXPOSE: each service listens on its own server.port, which compose and the Helm chart
# declare. Exec form, so java runs as PID 1 and receives SIGTERM for a graceful shutdown.
ENTRYPOINT ["java", "-jar", "application.jar"]
