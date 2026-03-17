# ---------- BUILD ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache de dependências separado do código
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ---------- RUNTIME ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl necessário para o healthcheck do docker-compose
RUN apk add --no-cache curl

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]