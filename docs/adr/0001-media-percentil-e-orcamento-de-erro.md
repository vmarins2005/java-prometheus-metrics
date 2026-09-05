# ADR 0001 — Média, percentil, e o que nenhum dos dois responde

Status: aceito · 2026-09-05 · supera: —

## Contexto

Mesma distribuição — 90 requisições em 20 ms, 8 em 200 ms, 2 em 1.500 ms:

| | valor | contra o SLO de 300 ms |
| --- | --- | --- |
| média | **64 ms** | folgadamente dentro |
| p99 | **1.790 ms** | quase seis vezes acima |
| fração dentro do SLO | 98,0% | |

A média é a métrica de latência mais publicada e a menos útil: ela dilui a cauda no volume.

## A afirmação que a medição derrubou

Este ADR ia dizer que o percentil resiste à diluição e a média não. Mil requisições rápidas a
mais, e nenhuma pessoa lenta a menos:

```
antes:  média   64 ms · p99 1790 ms · 2 fora do SLO
depois: média   24 ms · p99   22 ms · 2 fora do SLO
```

**O p99 também melhorou.** Ele é uma posição relativa dentro do conjunto: crescer o
denominador melhora os dois.

Foi uma previsão errada, e a correção é mais útil do que a afirmação original seria.

## Decisão

**Três instrumentos, três perguntas:**

| pergunta | instrumento |
| --- | --- |
| como está o sistema em geral? | média — e raramente é a pergunta |
| quão ruim é a experiência da minoria? | percentil |
| **quantas pessoas foram mal atendidas?** | **contagem fora do SLO** |

A terceira é a que não dilui. Duas pessoas esperaram um segundo e meio, e continuam sendo
duas depois de mil requisições rápidas.

É por isso que orçamento de erro se conta em **requisições**, e não em percentual instantâneo:
um serviço com 99,9% de disponibilidade e um bilhão de requisições falhou um milhão de vezes,
e "99,9%" não deixa isso visível.

## O balde no limiar

```java
Timer.builder("pedidos.consulta")
     .publishPercentileHistogram()
     .serviceLevelObjectives(SLO)
```

`serviceLevelObjectives` cria um balde exatamente em 300 ms:

```
pedidos_consulta_seconds_bucket{...,le="0.3"} 5
```

Com ele, "fração dentro do SLO" é uma divisão exata. Sem ele, a alternativa é
`histogram_quantile`, que **interpola linearmente entre baldes vizinhos** e devolve um número
que parece preciso e não é — especialmente na cauda, onde os baldes são largos.

## Por que histograma e não percentil pré-calculado

O Micrometer também sabe publicar percentis prontos (`publishPercentiles`). Eles são
calculados **na instância**, e percentis não somam: o p99 de três instâncias não é a média dos
três p99.

Com baldes, o Prometheus soma os baldes de todas as instâncias e calcula o percentil do
conjunto. É a única forma correta com mais de um processo.

O custo é o número de séries — 59 baldes neste projeto. Ver ADR 0003.

## Consequências

- \+ Percentil correto num serviço com várias instâncias.
- \+ A pergunta "estamos cumprindo o compromisso?" tem resposta exata, sem interpolação.
- − 59 séries por `Timer` com histograma. É o preço, e ele multiplica por cada tag.
- − A contagem fora do SLO não está exposta como métrica própria: ela é derivada de
  `count - bucket(le=0.3)` na consulta. Funciona, e é menos óbvio do que deveria.
- − Nada aqui implementa orçamento de erro de verdade — janela móvel, consumo acumulado,
  política de congelamento de deploy. O ADR argumenta por ele e o repositório não o tem.
