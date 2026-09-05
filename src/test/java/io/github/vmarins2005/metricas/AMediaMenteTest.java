package io.github.vmarins2005.metricas;

import static org.assertj.core.api.Assertions.assertThat;


import io.github.vmarins2005.metricas.instrumentacao.MedidorDePedidos;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * O painel diz que está tudo bem, e um em cada cem usuários espera um segundo e meio.
 *
 * <p>A média é a métrica de latência mais publicada e a menos útil. Ela dilui a cauda no
 * volume: quanto mais tráfego saudável, mais escondida fica a minoria que está sofrendo.
 *
 * <p>A distribuição deste teste é conhecida e alimentada de propósito, para que os dois
 * números sejam comparáveis em vez de sorteados.
 */
class AMediaMenteTest {

    /**
     * Registro proprio, e nao o da aplicacao.
     *
     * <p>A primeira versao destes testes usava o registro do contexto do Spring, e eles se
     * poluiram: as metricas de um apareceram no outro, e o p99 medido virou o de outra
     * distribuicao. Um registro por teste torna a medicao independente da ordem de execucao.
     */
    private final PrometheusMeterRegistry registro =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private final MedidorDePedidos medidor = new MedidorDePedidos(registro);

    @Test
    @DisplayName("mesma distribuição: a média cabe no SLO, o p99 fica cinco vezes acima")
    void aMediaDizUmaCoisaEOPercentilDizOutra() {
        // 90 requisições rápidas, 8 medianas, 2 muito lentas. É a forma que latência de
        // serviço tem, e é o que faz a média enganar.
        //
        // São duas lentas, e não uma, e o motivo é aritmético: com uma só em cem, o p99 é a
        // 99ª amostra ordenada — que ainda é rápida. A lenta seria o p100. A primeira versão
        // deste teste usava uma, esperava ver 1.500 ms no p99, e mediu 201 ms.
        registrar(90, Duration.ofMillis(20));
        registrar(8, Duration.ofMillis(200));
        registrar(2, Duration.ofMillis(1_500));

        var raspagem = raspar();
        double media = mediaEmMilissegundos(raspagem);
        double p99 = percentilEmMilissegundos(raspagem, 0.99);
        double dentroDoSlo = fracaoDentroDoSlo(raspagem);

        System.out.printf("média: %.0f ms · p99: %.0f ms · dentro do SLO de %d ms: %.1f%%%n",
                media, p99, MedidorDePedidos.SLO.toMillis(), 100 * dentroDoSlo);

        // A média cabe folgadamente dentro do SLO de 300 ms. Um painel com média num
        // limiar de 300 ms fica verde o dia inteiro.
        assertThat(media).isLessThan(MedidorDePedidos.SLO.toMillis());

        // E o p99 está quatro vezes acima do SLO.
        assertThat(p99).isGreaterThan(MedidorDePedidos.SLO.toMillis() * 3);

        // A métrica que realmente responde "estamos cumprindo o compromisso?" não é média
        // nem percentil: é a fração dentro do limiar. Ver ADR 0001.
        assertThat(dentroDoSlo).isBetween(0.98, 0.995);
    }

    @Test
    @DisplayName("mais tráfego saudável melhora todo agregado — e ninguém foi atendido melhor")
    void maisTrafegoSaudavelMelhoraTodoAgregado() {
        registrar(90, Duration.ofMillis(20));
        registrar(8, Duration.ofMillis(200));
        registrar(2, Duration.ofMillis(1_500));

        var antes = raspar();
        double mediaAntes = mediaEmMilissegundos(antes);
        double p99Antes = percentilEmMilissegundos(antes, 0.99);
        double foraDoSloAntes = quantidadeForaDoSlo(antes);

        // Mil requisições rápidas a mais. Nenhuma pessoa lenta a menos.
        registrar(1_000, Duration.ofMillis(20));
        var depois = raspar();

        System.out.printf("antes:  média %.0f ms · p99 %.0f ms · %.0f fora do SLO%n",
                mediaAntes, p99Antes, foraDoSloAntes);
        System.out.printf("depois: média %.0f ms · p99 %.0f ms · %.0f fora do SLO%n",
                mediaEmMilissegundos(depois), percentilEmMilissegundos(depois, 0.99),
                quantidadeForaDoSlo(depois));

        // Este teste ia dizer que o percentil resiste à diluição e a média não. Ele mediu, e
        // não é verdade: com mil requisições rápidas a mais, o p99 também melhora, porque
        // ele também é uma posição relativa dentro do conjunto.
        assertThat(mediaEmMilissegundos(depois)).isLessThan(mediaAntes);
        assertThat(percentilEmMilissegundos(depois, 0.99)).isLessThan(p99Antes);

        // O que não melhora é a contagem absoluta de quem esperou além do SLO. Duas pessoas
        // esperaram um segundo e meio, e continuam sendo duas.
        //
        // É por isso que orçamento de erro é contado em requisições, e não em percentual
        // instantâneo. Ver ADR 0001.
        assertThat(quantidadeForaDoSlo(depois)).isEqualTo(foraDoSloAntes);
    }

    private static double quantidadeForaDoSlo(String raspagem) {
        double limiar = MedidorDePedidos.SLO.toMillis() / 1_000.0;
        return valorDe(raspagem, "pedidos_consulta_seconds_count")
                - baldes(raspagem).getOrDefault(limiar, 0.0);
    }

    private void registrar(int vezes, Duration duracao) {
        for (int i = 0; i < vezes; i++) {
            medidor.registrar(duracao, "sucesso");
        }
    }

    private String raspar() {
        return registro.scrape();
    }

    private static double mediaEmMilissegundos(String raspagem) {
        double soma = valorDe(raspagem, "pedidos_consulta_seconds_sum");
        double contagem = valorDe(raspagem, "pedidos_consulta_seconds_count");
        return 1_000 * soma / contagem;
    }

    /** O mesmo cálculo que o `histogram_quantile` do Prometheus faz: acha o balde. */
    private static double percentilEmMilissegundos(String raspagem, double percentil) {
        var baldes = baldes(raspagem);
        double total = valorDe(raspagem, "pedidos_consulta_seconds_count");
        double alvo = percentil * total;
        return 1_000 * baldes.entrySet().stream()
                .filter(balde -> balde.getValue() >= alvo)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static double fracaoDentroDoSlo(String raspagem) {
        double limiar = MedidorDePedidos.SLO.toMillis() / 1_000.0;
        double dentro = baldes(raspagem).getOrDefault(limiar, 0.0);
        return dentro / valorDe(raspagem, "pedidos_consulta_seconds_count");
    }

    /** Baldes cumulativos, do menor para o maior, como o Prometheus os publica. */
    private static Map<Double, Double> baldes(String raspagem) {
        var porLimite = new LinkedHashMap<Double, Double>();
        RaspagemDoPrometheusTest.seriesQueComecamCom(raspagem, "pedidos_consulta_seconds_bucket")
                .forEach(linha -> {
                    var le = linha.replaceAll(".*le=\"([^\"]+)\".*", "$1");
                    var valor = linha.substring(linha.lastIndexOf(' ') + 1);
                    porLimite.put(
                            le.equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(le),
                            Double.parseDouble(valor));
                });
        return porLimite;
    }

    private static double valorDe(String raspagem, String serie) {
        var linha = RaspagemDoPrometheusTest.seriesQueComecamCom(raspagem, serie).getFirst();
        return Double.parseDouble(linha.substring(linha.lastIndexOf(' ') + 1));
    }
}
