# syntax=docker/dockerfile:1
# Build context: mcp-servers/ledger-mcp. Tests run in CI, not in the image build.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,id=m2-ledger-mcp,sharing=locked,target=/root/.m2 sh mvnw -B -q package -DskipTests && cp target/ledger-mcp-*.jar /app.jar

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
