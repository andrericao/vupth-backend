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

# Memoria (pr70-deploy-timeout, 04/10): o Render Free mata o container acima de 512 MiB. Com 75% de heap
# (384 MiB) mais metaspace, code cache e threads, o boot passava do teto ("Out of memory (used over 512Mi)")
# e o staging ficou preso no deploy anterior. O padrao cabe em 512 MiB: heap em 50%, code cache e
# metaspace com teto, Serial GC (o de menos memoria fora do heap). Com mais memoria (Railway), troque
# pelo JAVA_OPTS do painel.
# pr70-rollback-recusado (05/10): mesmo assim o processo caia no meio do Newman do Actions (502 no 180o
# request em 04/10, no 156o em 05/10, endpoints diferentes) — parado ja eram ~427 MiB, e sob carga o C2
# compila em arenas nativas que passam do teto. So o C1 (TieredStopAtLevel=1): bem menos memoria nativa
# e menos CPU para compilar (o boot levava 234 s com 0,1 CPU), ao custo de pico de desempenho que nao
# importa aqui. Heap em 45% (~230 MiB) e code cache em 48m deixam folga para o resto.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=45.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "cd /app/application && exec java $JAVA_OPTS $( [ -f app.jsa ] && echo -XX:SharedArchiveFile=app.jsa ) -jar app.jar"]
