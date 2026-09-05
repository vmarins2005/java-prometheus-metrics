FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /fonte
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src/ src/
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 servico
USER 10001
WORKDIR /app
COPY --from=build --chown=10001 /fonte/target/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
