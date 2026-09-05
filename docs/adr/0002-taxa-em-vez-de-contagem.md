# ADR 0002 — Taxa em vez de contagem, e o alerta que quase ninguém escreve

Status: aceito · 2026-09-05 · supera: —

## Contexto

Um contador de erro sozinho não é um sinal.

> "10 erros" não diz nada. Dez de dez é catástrofe; dez de dez milhões é terça-feira.

Um alerta sobre contagem absoluta tem os dois defeitos ao mesmo tempo: dispara no pico de
tráfego, quando a taxa está normal, e fica calado na madrugada, quando 100% das poucas
requisições estão falhando.

## Decisão

**Todo contador de desfecho carrega a tag do desfecho**, e o denominador vem de graça:

```
pedidos_consulta_resultado_total{...,resultado="erro"}    1.0
pedidos_consulta_resultado_total{...,resultado="sucesso"} 2.0
```

Duas séries, e a divisão entre elas é a taxa:

```promql
sum(rate(pedidos_consulta_resultado_total{resultado="erro"}[1m]))
  /
sum(rate(pedidos_consulta_resultado_total[1m]))
  > 0.05
```

Medido de verdade pelo `disparar-alerta.sh`, com falha provocada em 40% das consultas:

```
{"alertname":"TaxaDeErroAlta","severidade":"critico",
 "detalhe":"Taxa atual: 40.74%"}
```

O alerta percorreu o caminho inteiro: instrumentação, raspagem, regra, avaliação e chegada ao
Alertmanager. Não é configuração que parece certa — é comportamento observado.

## `for: 15s`

A condição precisa se manter por quinze segundos antes de virar alerta. Sem isso, um pico de
cinco segundos numa raspagem acorda alguém.

Quinze segundos é curto porque este é um repositório de estudo e o script precisa terminar.
Num serviço real, o valor sai do tempo que o time aceita ficar sem saber — e ele é minutos,
não segundos.

## O alerta que quase ninguém escreve

```promql
up{job="servico"} == 0
```

Um serviço morto **não dispara nada**. Ele para de produzir métricas, e todos os outros
alertas ficam sem dados para avaliar — uma expressão sem série não é falsa, ela é vazia, e
uma expressão vazia não alerta.

O painel fica em branco, o alerta de erro fica calado, e silêncio parece calmaria.

É o modo de falha mais perigoso de um sistema de alertas, e a regra que o cobre tem uma linha.

## O que ficou de fora

**Alerta sobre saturação e sobre tráfego.** Os quatro sinais de ouro são latência, erro,
tráfego e saturação; este projeto tem os dois primeiros. Uma queda brusca de tráfego é
frequentemente o primeiro sinal de um problema a montante, e não há regra para ela aqui.

**Roteamento.** O Alertmanager deste projeto tem um receptor chamado `buraco-negro`, que não
envia para lugar nenhum. Rotear por severidade, silenciar durante janela de manutenção e
evitar tempestade de alertas são decisões reais, e nenhuma está aqui.

## Consequências

- \+ O alerta é sobre proporção, e funciona igual em pico e em madrugada.
- \+ Existe alerta para "parei de receber dados".
- − Uma taxa alta com volume baixo dispara: três erros em cinco requisições são 60%. Num
  serviço com pouco tráfego, isso vira ruído, e a correção usual — exigir um volume mínimo —
  não está aqui.
- − `for: 15s` é curto demais para produção e está assim para o script terminar.
- − Nenhum alerta é entregue a lugar nenhum. O caminho medido termina no Alertmanager.
