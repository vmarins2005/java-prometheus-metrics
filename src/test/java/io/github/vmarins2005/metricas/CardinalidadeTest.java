package io.github.vmarins2005.metricas;

import static org.assertj.core.api.Assertions.assertThat;


import io.github.vmarins2005.metricas.instrumentacao.MedidorDePedidos;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * A tag que parece útil e derruba o Prometheus.
 *
 * <p>"Quero ver o tempo por pedido" é um pedido razoável de produto e uma decisão terrível de
 * instrumentação. Cada valor distinto de uma tag cria uma <b>série temporal nova</b>, e cada
 * série ocupa memória no Prometheus — para sempre, porque a série não some quando o pedido
 * deixa de existir.
 *
 * <p>Métrica não é log. Log guarda o caso; métrica guarda a forma do conjunto.
 */
class CardinalidadeTest {

    private static final int PEDIDOS = 50;

    private final PrometheusMeterRegistry registro =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private final MedidorDePedidos medidor = new MedidorDePedidos(registro);

    @Test
    @DisplayName("cinquenta pedidos distintos com o id na tag: cinquenta vezes mais séries")
    void cinquentaPedidosDistintosNaTag() {
        int antes = seriesDe("pedidos_consulta_por_id").size();

        for (int i = 0; i < PEDIDOS; i++) {
            medidor.medirComTagDeAltaCardinalidade("PED-" + i, Duration.ofMillis(20));
            // A instrumentação correta, com o mesmo volume de chamadas, para comparar.
            medidor.registrar(Duration.ofMillis(20), "sucesso");
        }

        int comIdNaTag = seriesDe("pedidos_consulta_por_id").size();
        int semIdNaTag = seriesDe("pedidos_consulta_seconds").size();

        System.out.printf("%d pedidos distintos:%n", PEDIDOS);
        System.out.printf("  com o id na tag: %d séries (antes eram %d)%n", comIdNaTag, antes);
        System.out.printf("  sem o id na tag: %d séries, e esse número não cresce com o tráfego%n",
                semIdNaTag);

        // Três séries por pedido — contagem, soma e máximo — porque este Timer não tem
        // histograma. Com histograma seriam mais de sessenta por pedido.
        assertThat(comIdNaTag).isGreaterThanOrEqualTo(PEDIDOS * 3);

        // A instrumentação correta tem um número fixo de séries: ele depende dos baldes do
        // histograma, e não do número de pedidos. Mil pedidos não mudam nada aqui.
        int aposMaisPedidos = medirMaisEContar();
        System.out.printf("  depois de mais %d pedidos: %d séries sem id, %d com id%n",
                PEDIDOS, semIdNaTag, aposMaisPedidos);

        assertThat(seriesDe("pedidos_consulta_seconds")).hasSize(semIdNaTag);
        assertThat(aposMaisPedidos).isGreaterThan(comIdNaTag);
    }

    @Test
    @DisplayName("o mesmo id repetido não cria série nova — o problema é a variedade")
    void oMesmoIdRepetidoNaoCriaSerieNova() {
        medidor.medirComTagDeAltaCardinalidade("PED-SEMPRE-O-MESMO", Duration.ofMillis(20));
        int comUm = seriesDe("pedidos_consulta_por_id").size();

        for (int i = 0; i < 100; i++) {
            medidor.medirComTagDeAltaCardinalidade("PED-SEMPRE-O-MESMO", Duration.ofMillis(20));
        }

        // Cem chamadas, uma série. O custo não é o volume de requisições: é a quantidade de
        // valores distintos que a tag pode assumir. Uma tag de status HTTP tem cinco valores
        // e é barata; uma tag de id de pedido é ilimitada.
        assertThat(seriesDe("pedidos_consulta_por_id")).hasSize(comUm);
    }

    private int medirMaisEContar() {
        for (int i = PEDIDOS; i < PEDIDOS * 2; i++) {
            medidor.medirComTagDeAltaCardinalidade("PED-" + i, Duration.ofMillis(20));
        }
        return seriesDe("pedidos_consulta_por_id").size();
    }

    private java.util.List<String> seriesDe(String prefixo) {
        return RaspagemDoPrometheusTest.seriesQueComecamCom(registro.scrape(), prefixo);
    }
}
