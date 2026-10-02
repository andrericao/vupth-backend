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

# Usuário sem privilégio: o container não roda como root
RUN addgroup -S vupth && adduser -S vupth -G vupth

# Jar extraído (o layout que o CDS exige) em /app/application
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application && rm app.jar

# CDS: um boot de treino grava as classes carregadas em app.jsa e a partida real as lê prontas
# (JVM mais rápida no cold start). O treino para no refresh do contexto e não fala com banco:
# Liquibase desligado, Hibernate sem ler metadados e URL de mentira. Se o treino falhar, o build
# segue sem o arquivo, e a partida fica sem CDS, como antes.
RUN cd application && timeout 180 java -XX:ArchiveClassesAtExit=app.jsa \
      -Dspring.context.exit=onRefresh \
      -Dspring.liquibase.enabled=false \
      -Dspring.datasource.url=jdbc:postgresql://localhost:5432/cds \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar > /dev/null 2>&1 \
    && echo "CDS: app.jsa gerado" || (rm -f app.jsa; echo "CDS: treino falhou, imagem sem CDS")

RUN chown -R vupth:vupth /app
USER vupth

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "cd /app/application && exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 $( [ -f app.jsa ] && echo -XX:SharedArchiveFile=app.jsa ) -jar app.jar"]
