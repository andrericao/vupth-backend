# Backup diario dentro do Railway (S2, Andre 16/10): roda como servico cron na rede privada do projeto e fala
# com o Postgres pelo railway.internal, sem o proxy TCP publico. O pg_dump 18 le o servidor 18 (Railway 18.6).
FROM postgres:18-alpine
RUN apk add --no-cache aws-cli
COPY backup.sh /backup.sh
ENTRYPOINT ["sh", "/backup.sh"]
