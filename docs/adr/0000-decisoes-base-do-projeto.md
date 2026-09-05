# ADR 0000 — Decisões base do projeto

Status: aceito · 2026-09-05 · supera: —

## Contexto

O projeto existe para transformar "temos observabilidade" em números: quantas séries cada
decisão custa, o que a média esconde, e um alerta que percorre o caminho inteiro até o
Alertmanager.

## Decisões

### 1. Os testes raspam `/actuator/prometheus`, e não o registro do Micrometer

Olhar o `MeterRegistry` por dentro diria o que a aplicação coletou. Raspar o endpoint diz o
que o **Prometheus vai receber** — que é outra coisa, porque passa pela conversão de nomes,
pelas tags comuns e pela decisão de publicar ou não os baldes.

Contar linhas do texto raspado é a única forma honesta de afirmar coisas sobre cardinalidade
e sobre quais consultas serão possíveis depois.

E não precisa de contêiner: o endpoint é servido pela própria aplicação.

### 2. Cada teste de medição tem o seu próprio registro

`AMediaMenteTest` e `CardinalidadeTest` criam um `PrometheusMeterRegistry` novo.

A primeira versão usava o registro do contexto do Spring, e os dois se poluíram: as métricas
de um apareceram no outro, e o p99 medido virou o de outra distribuição. O teste passou a
depender da ordem de execução, que é o pior tipo de teste instável — ele fica verde na
máquina de quem escreveu.

### 3. A distribuição é alimentada, não cronometrada

`medidor.registrar(Duration, resultado)` grava uma duração exata. Cronometrar de verdade daria
números diferentes a cada execução, e a comparação entre média e percentil perderia o sentido.

O serviço de verdade dorme com uma distribuição log-normal — é o que o script de alerta usa.

### 4. O alerta é medido de ponta a ponta, num script

`disparar-alerta.sh` sobe quatro contêineres e leva minutos. Não cabe na suíte, e o que ele
mede não dá para medir de outro jeito: regra do Prometheus é uma expressão avaliada pelo
Prometheus, e testá-la sem ele seria testar a minha leitura da documentação.

### 5. O Grafana é opcional

Sobe com `--profile painel`, com a fonte de dados provisionada e nenhum painel montado.

Um dashboard exportado como JSON é fácil de publicar e difícil de manter honesto: ele vira uma
captura de tela bonita que ninguém verifica. Este repositório mede em vez de ilustrar.

## Consequências

- A suíte roda em ~15 segundos e não precisa de Docker.
- O script leva minutos e precisa de quatro contêineres.
- Há instrumentação errada de propósito no código de produção, marcada no Javadoc.
- Nada aqui mede custo de armazenamento das séries, só quantas são.
