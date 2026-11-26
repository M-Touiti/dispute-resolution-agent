# syntax=docker/dockerfile:1
# Build context: investigator/. Tests run in CI, not in the image build. Skills and fixtures are mounted at run time
# (/app/skills, /app/fixtures): they live outside this module and the recording session writes fixtures back.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY domain/ domain/
COPY application/ application/
COPY adapters/ adapters/
COPY bootstrap/ bootstrap/
RUN --mount=type=cache,id=m2-investigator,sharing=locked,target=/root/.m2 sh mvnw -B -q package -DskipTests \
    && cp bootstrap/target/investigator-bootstrap-*.jar /app.jar

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
ENV INVESTIGATOR_SKILLS_DIR=/app/skills LLM_FIXTURES_DIR=/app/fixtures
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
