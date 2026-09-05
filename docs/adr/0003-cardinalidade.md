# ADR 0003 — O custo de uma tag, medido

Status: aceito · 2026-09-05 · supera: —

## Contexto

"Quero ver o tempo por pedido" é um pedido razoável de produto e uma decisão terrível de
instrumentação.

Medido:

| | 50 pedidos distintos | 100 pedidos distintos |
| --- | --- | --- |
| com o id na tag | **150 séries** | **300 séries** |
| sem o id na tag | 62 séries | **62 séries** |

Cada valor distinto de uma tag cria uma série temporal nova. Ela ocupa memória no Prometheus e
**não some quando o pedido deixa de existir**: a série continua lá até a retenção expirar.

São três séries por pedido aqui — contagem, soma e máximo. Com histograma seriam mais de
sessenta por pedido, porque cada balde é uma série.

## O que custa não é o volume

```
100 chamadas com o mesmo id → 1 série
100 chamadas com ids distintos → 300 séries
```

O custo é a **variedade** de valores que a tag pode assumir, e não quantas vezes ela é
registrada.

## Decisão

**Nenhuma tag recebe valor de cardinalidade ilimitada.**

| tag | valores possíveis | veredito |
| --- | --- | --- |
| `resultado` | sucesso, erro | barata |
| `status` HTTP | ~5 famílias | barata |
| `aplicacao`, `ambiente` | dezenas | barata |
| `endpoint` | dezenas, se for o **padrão** da rota | barata |
| `pedido`, `usuario`, `cpf` | ilimitados | **proibida** |
| `url` com id dentro | ilimitados | **proibida** |

A última linha é a armadilha silenciosa: `/pedidos/123` como valor de tag é o id disfarçado. O
Spring publica `uri` com o **padrão** (`/pedidos/{id}`) justamente por isso, e uma
instrumentação feita à mão costuma publicar o caminho concreto sem perceber.

## O critério

> Métrica não é log. Log guarda o caso; métrica guarda a forma do conjunto.

Quando a pergunta é "o que aconteceu com o pedido PED-42", a resposta é log ou trace — os dois
guardam o caso individual e têm retenção pensada para isso. Métrica responde "quantos, quão
rápido, com que taxa de erro", e essas perguntas não precisam do id.

## Como isso derruba um Prometheus

A série vive na memória do Prometheus enquanto estiver na janela de retenção. Um serviço com
uma tag de id e mil pedidos por hora cria vinte e quatro mil séries por dia — e a memória do
Prometheus cresce com o número de séries, não com o número de amostras.

O sintoma é o Prometheus sendo morto por falta de memória, e a causa está numa linha de código
de um serviço que ninguém suspeitou.

## Consequências

- \+ O número de séries deste serviço é fixo: 62, independentemente do tráfego.
- \+ O custo está medido, e o teste reprova se a tag de id voltar.
- − Não existe forma de responder "quanto demorou o pedido PED-42" com métrica. Precisa de
  trace, e ele é assunto de outro projeto da série.
- − O teste mede a cardinalidade **deste** medidor. Nada aqui impede alguém de criar uma tag
  ruim em outro lugar — um teste que raspe o endpoint e reprove acima de N séries seria a
  rede de verdade, e não está implementado.
- − 62 séries por `Timer` com histograma também não é pouco. A escolha de ter histograma é
  cara, e é justificada no ADR 0001.
