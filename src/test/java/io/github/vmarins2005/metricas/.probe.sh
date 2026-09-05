#!/usr/bin/env bash
export JAVA_HOME=~/ferramentas/jdk21
cd /mnt/c/projetos/estudos-java/java-metricas-e-alerta
./mvnw -B clean test > /tmp/m2.txt 2>&1
echo "exit=$?"
grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE' /tmp/m2.txt | tail -6
echo "--- medicoes ---"
grep -E 'histograma:|balde no limiar|média|pedidos distintos|com o id na tag|sem o id na tag|depois de mais' /tmp/m2.txt | head -12
grep -E '^\[ERROR\]   ' /tmp/m2.txt | head -6
