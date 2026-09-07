# Micrometer, Prometheus, e um alerta que dispara

O painel diz que está tudo bem, e um em cada cinquenta usuários espera um segundo e meio.

Décimo nono de uma série em que cada repositório isola um conceito.

O pipeline em [`.github/workflows/ci.yaml`](.github/workflows/ci.yaml) está desarmado de
propósito — roda só por acionamento manual.

## Como rodar

```bash
./mvnw verify           # 12 testes, ~15 s, sem contêiner nenhum
./disparar-alerta.sh    # sobe a pilha, provoca a falha, espera o alerta — alguns minutos
```

Os testes raspam `/actuator/prometheus` e contam séries. É o mesmo texto que o Prometheus
busca, e contar linhas nele é a única forma honesta de afirmar o que será possível consultar
depois.

## A média mente

Mesma distribuição — 90 requisições em 20 ms, 8 em 200 ms, 2 em 1.500 ms:

| | valor | contra o SLO de 300 ms |
| --- | --- | --- |
| média | **64 ms** | folgadamente dentro |
| p99 | **1.790 ms** | quase seis vezes acima |
| fração dentro do SLO | 98,0% | |

Um painel com média num limiar de 300 ms fica verde o dia inteiro.

## E o percentil também dilui

Este README ia dizer que o percentil resiste à diluição e a média não. **Não é verdade**, e a
medição derrubou a afirmação. Mil requisições rápidas a mais, e nenhuma pessoa lenta a menos:

```
antes:  média   64 ms · p99 1790 ms · 2 fora do SLO
depois: média   24 ms · p99   22 ms · 2 fora do SLO
```

O p99 também é uma posição relativa dentro do conjunto. Crescer o denominador melhora os dois.

**O que não melhora é a contagem absoluta de quem esperou além do SLO.** Duas pessoas
esperaram um segundo e meio, e continuam sendo duas.

> É por isso que orçamento de erro se conta em requisições, e não em percentual instantâneo.

## Sem histograma não existe percentil

```
com histograma: 59 séries de balde
sem histograma:  0 séries de balde
```

Um `Timer` do Micrometer publica contagem, soma e máximo. Só isso. Dá para calcular a média —
e a média é o que este repositório está tentando não usar.

`histogram_quantile` do Prometheus precisa dos baldes, e eles só existem com
`publishPercentileHistogram()`.

E há um balde **exatamente no limiar do SLO**:

```
pedidos_consulta_seconds_bucket{...,le="0.3"} 5
```

`serviceLevelObjectives(SLO)` cria esse balde. Com ele, "fração dentro do SLO" é uma divisão
exata; sem ele, é `histogram_quantile` interpolando entre baldes vizinhos e devolvendo um
número aproximado.

## A tag que derruba o Prometheus

"Quero ver o tempo por pedido" é um pedido razoável de produto e uma decisão terrível de
instrumentação:

| | 50 pedidos distintos | 100 pedidos distintos |
| --- | --- | --- |
| com o id na tag | **150 séries** | **300 séries** |
| sem o id na tag | 62 séries | **62 séries** |

Cada valor distinto de uma tag cria uma série temporal nova, e ela ocupa memória no Prometheus
**para sempre** — a série não some quando o pedido deixa de existir.

São três séries por pedido aqui — contagem, soma e máximo — porque esse `Timer` não tem
histograma. **Com histograma seriam mais de sessenta por pedido.**

E o custo não é o volume de requisições, é a variedade: cem chamadas com o mesmo id criam uma
série só. Uma tag de status HTTP tem cinco valores e é barata; uma tag de id é ilimitada.

> Métrica não é log. Log guarda o caso; métrica guarda a forma do conjunto.

## Taxa, não contagem

```
pedidos_consulta_resultado_total{...,resultado="erro"}    1.0
pedidos_consulta_resultado_total{...,resultado="sucesso"} 2.0
```

Duas séries, uma por desfecho. É isso que permite escrever `erro / (erro + sucesso)`.

"10 erros" não diz nada: dez de dez é catástrofe, dez de dez milhões é terça-feira. Um contador
só de erro não tem denominador, e um alerta em cima dele dispara no pico de tráfego e fica
calado quando o serviço está morto.

## O alerta que dispara

`./disparar-alerta.sh` sobe serviço, Prometheus e Alertmanager, gera tráfego saudável, provoca
falha em 40% das consultas, e espera:

```
=== alertas no Alertmanager ===
{"annotations":{"detalhe":"Taxa atual: 40.74%",
                "resumo":"Mais de 5% das consultas de pedido estao falhando"},
 "labels":{"alertname":"TaxaDeErroAlta","severidade":"critico"}}

=== o que o Prometheus calculou ===
"value":[1788628428.019,"0.40740740740740744"]
```

Não é um alerta configurado: é um alerta que **percorreu o caminho inteiro** — instrumentação,
raspagem, regra, avaliação, e chegada ao Alertmanager.

### As três regras

| alerta | expressão | por quê |
| --- | --- | --- |
| `TaxaDeErroAlta` | `erro / total > 5%` | taxa, não contagem |
| `SloDeLatenciaEstourado` | `bucket(le=0.3) / total < 95%` | fração exata, sem interpolar |
| `ServicoSemRaspagem` | `up{job="servico"} == 0` | **o que quase ninguém escreve** |

A terceira é a que falta na maioria dos sistemas. Um serviço morto não dispara nada: ele
simplesmente para de produzir métricas, e todos os outros alertas ficam sem dados para
avaliar. Silêncio parece calmaria.

## Decisões registradas

| ADR | Assunto |
| --- | --- |
| [0000](docs/adr/0000-decisoes-base-do-projeto.md) | Escopo, e por que os testes raspam o endpoint em vez de olhar o registro |
| [0001](docs/adr/0001-media-percentil-e-orcamento-de-erro.md) | Média, percentil, e o que nenhum dos dois responde |
| [0002](docs/adr/0002-taxa-em-vez-de-contagem.md) | Denominador, e o alerta que quase ninguém escreve |
| [0003](docs/adr/0003-cardinalidade.md) | O custo de uma tag, medido |

## Código errado de propósito

`MedidorDePedidos.medirComTagDeAltaCardinalidade` — a instrumentação com o id na tag. Está
marcada no Javadoc e existe para que a explosão de séries seja medida.

## O que não está aqui

- **Painel do Grafana com dados.** O Compose sobe o Grafana no perfil `painel`, com a fonte de
  dados provisionada e nenhum painel montado. Um dashboard exportado como JSON é fácil de
  publicar e difícil de manter honesto, e este repositório mede em vez de ilustrar.
- **Métrica de negócio.** `pedidos.consultas.em.andamento` é um `Gauge` técnico. Quais métricas de
  negócio publicar é decisão de quem conhece o domínio.
- **Exemplares** (ligação métrica → trace). É o assunto do `java-opentelemetry-tracing`.
- **Custo de armazenamento.** Nada aqui mede quanto disco as séries ocupam de fato, só quantas
  são.

## Exercícios

1. **Tire `publishPercentileHistogram()`** e rode `RaspagemDoPrometheusTest`. O teste falha
   dizendo exatamente o que sumiu.
2. **Chame `/pedidos/{id}/com-tag-de-id` mil vezes** com ids distintos e conte as linhas de
   `/actuator/prometheus`. Depois some `publishPercentileHistogram()` naquele `Timer`.
3. **Troque o alerta de taxa por um de contagem** (`> 10 erros`) e rode o script durante o
   tráfego saudável. Ele dispara.
4. **Pare o contêiner do serviço** com a pilha no ar e veja qual alerta dispara. Depois
   remova a regra `ServicoSemRaspagem` e repita.
5. **Aumente `scrape_interval` para 60 s** e provoque um pico de dez segundos. Ele não existe.

## Regras de trabalho neste repositório

- Latência se mede com histograma; média é para quando não importa.
- Todo contador de erro tem um contador de total ao lado.
- Nenhuma tag recebe valor de cardinalidade ilimitada.
- Todo conjunto de alertas tem um alerta para "parei de receber dados".

## O que eu faria diferente

_A preencher depois de usar._
