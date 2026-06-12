#!/bin/bash
URL="https://vupth-backend-ia.onrender.com/actuator/health"
INTERVAL=840 # 14 minutos

echo "Keep-alive Render staging — ping a cada 14min"
echo "URL: $URL"
echo "Ctrl+C para parar"
echo "---"

while true; do
  STATUS=$(curl --connect-timeout 10 --max-time 120 -s -o /dev/null -w "%{http_code}" "$URL" 2>/dev/null)
  echo "[$(date '+%H:%M:%S')] Status: $STATUS"
  sleep $INTERVAL
done
