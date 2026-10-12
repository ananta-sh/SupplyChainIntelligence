FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY data ./data
COPY artifacts ./artifacts
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/supply-chain-intelligence-1.0.0.jar /app/app.jar
COPY data/raw /app/data/raw
COPY artifacts /app/artifacts
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
