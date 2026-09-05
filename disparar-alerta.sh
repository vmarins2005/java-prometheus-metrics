#!/usr/bin/env bash
#
# Sobe a pilha, gera trafego, provoca falha, e espera o alerta disparar de verdade.
#
# Nao esta na suite de testes: sobe quatro conteineres e leva minutos. O que a suite mede e
# o que a aplicacao publica; o que este script mede e o caminho inteiro ate o Alertmanager.
#
#   ./disparar-alerta.sh

set -uo pipefail
cd "$(dirname "$0")/observabilidade"

encerrar() {
    echo
    echo "derrubando a pilha..."
    docker compose down -v > /dev/null 2>&1
}
trap encerrar EXIT

echo "subindo servico, prometheus e alertmanager..."
docker compose up -d --build > /tmp/compose.log 2>&1 || { tail -20 /tmp/compose.log; exit 1; }

echo -n "esperando o prometheus raspar o servico"
for _ in $(seq 1 60); do
    ativo=$(curl -s "http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22servico%22%7D" \
              | grep -o '"value":\[[^]]*,"1"\]' | head -1)
    if [ -n "$ativo" ]; then echo " ok"; break; fi
    echo -n "."
    sleep 2
done

echo "gerando trafego saudavel por 20 s..."
fim=$(( $(date +%s) + 20 ))
while [ "$(date +%s)" -lt "$fim" ]; do
    curl -s -o /dev/null "http://localhost:8080/pedidos/PED-$RANDOM"
done

echo "provocando falha em 40% das consultas..."
curl -s -X POST "http://localhost:8080/caos/falha?porcentagem=40" > /dev/null

echo -n "esperando o alerta disparar"
disparou=""
for _ in $(seq 1 60); do
    disparou=$(curl -s http://localhost:9093/api/v2/alerts | grep -o '"alertname":"[^"]*"' | sort -u)
    if [ -n "$disparou" ]; then echo " ok"; break; fi
    curl -s -o /dev/null "http://localhost:8080/pedidos/PED-$RANDOM"
    echo -n "."
    sleep 2
done

echo
echo "=== alertas no Alertmanager ==="
curl -s http://localhost:9093/api/v2/alerts \
  | tr ',' '\n' | grep -E '"alertname"|"severidade"|"resumo"|"detalhe"|"status"' | head -20

echo
echo "=== o que o Prometheus calculou ==="
curl -s "http://localhost:9090/api/v1/query?query=sum(rate(pedidos_consulta_resultado_total%7Bresultado%3D%22erro%22%7D%5B1m%5D))/sum(rate(pedidos_consulta_resultado_total%5B1m%5D))" \
  | grep -o '"value":\[[^]]*\]'
