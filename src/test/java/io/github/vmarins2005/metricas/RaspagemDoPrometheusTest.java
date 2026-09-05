package io.github.vmarins2005.metricas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.vmarins2005.metricas.pedidos.ConsultaDePedidos;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O que o Prometheus realmente enxerga.
 *
 * <p>Todo teste aqui raspa {@code /actuator/prometheus} e conta linhas. É o mesmo texto que o
 * Prometheus busca, e contar séries nele é a única forma honesta de afirmar coisas sobre
 * cardinalidade e sobre quais consultas serão possíveis depois.
 *
 * <p>Não precisa de contêiner: o endpoint é servido pela própria aplicação.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RaspagemDoPrometheusTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConsultaDePedidos consulta;

    @Test
    @DisplayName("sem histograma não existe percentil — só contagem, soma e máximo")
    void semHistogramaNaoExistePercentil() throws Exception {
        consulta.medirDuracaoConhecida(Duration.ofMillis(20), "sucesso");
        var raspagem = raspar();

        var comBaldes = seriesQueComecamCom(raspagem, "pedidos_consulta_seconds_bucket");
        var semBaldes = seriesQueComecamCom(raspagem, "pedidos_consulta_sem_histograma_seconds_bucket");

        System.out.printf("com histograma: %d séries de balde%n", comBaldes.size());
        System.out.printf("sem histograma: %d séries de balde%n", semBaldes.size());

        // `histogram_quantile` do Prometheus precisa dos baldes. Sem eles, o máximo que se
        // consegue calcular é a média — soma dividida por contagem. Ver ADR 0001.
        assertThat(comBaldes).isNotEmpty();
        assertThat(semBaldes).isEmpty();

        // Os dois publicam contagem e soma; só um publica o resto.
        assertThat(seriesQueComecamCom(raspagem, "pedidos_consulta_sem_histograma_seconds_count"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("existe um balde exatamente no limiar do SLO")
    void existeUmBaldeNoLimiarDoSlo() throws Exception {
        consulta.medirDuracaoConhecida(Duration.ofMillis(20), "sucesso");
        var raspagem = raspar();

        // 300 ms = 0.3 s. Com um balde exatamente aí, a consulta "fração dentro do SLO" é uma
        // divisão exata, sem interpolar entre baldes vizinhos — que é o que o
        // `histogram_quantile` faz e onde ele erra.
        var noLimiar = seriesQueComecamCom(raspagem, "pedidos_consulta_seconds_bucket").stream()
                .filter(linha -> linha.contains("le=\"0.3\""))
                .toList();

        System.out.printf("balde no limiar do SLO: %s%n",
                noLimiar.isEmpty() ? "AUSENTE" : noLimiar.getFirst());

        assertThat(noLimiar).isNotEmpty();
    }

    @Test
    @DisplayName("o contador tem tag de resultado, então dá para calcular taxa")
    void oContadorTemTagDeResultado() throws Exception {
        consulta.medirDuracaoConhecida(Duration.ofMillis(10), "sucesso");
        consulta.medirDuracaoConhecida(Duration.ofMillis(10), "erro");
        var raspagem = raspar();

        var porResultado = seriesQueComecamCom(raspagem, "pedidos_consulta_resultado_total");
        porResultado.forEach(linha -> System.out.println("  " + linha));

        // Duas séries, uma por desfecho. É isso que permite escrever
        // erro / (erro + sucesso) — uma taxa. Um contador só de erro não permite.
        // Ver ADR 0002.
        assertThat(porResultado).hasSize(2);
        assertThat(porResultado).anyMatch(linha -> linha.contains("resultado=\"sucesso\""));
        assertThat(porResultado).anyMatch(linha -> linha.contains("resultado=\"erro\""));
    }

    @Test
    @DisplayName("toda série carrega aplicação e ambiente")
    void todaSerieCarregaAplicacaoEAmbiente() throws Exception {
        consulta.medirDuracaoConhecida(Duration.ofMillis(10), "sucesso");
        var raspagem = raspar();

        var doProjeto = seriesQueComecamCom(raspagem, "pedidos_");

        // Sem estas tags, duas instâncias do mesmo serviço publicam séries idênticas, e o
        // Prometheus não consegue distinguir "produção" de "homologação" nem somar o que
        // deveria somar.
        assertThat(doProjeto).isNotEmpty();
        assertThat(doProjeto).allMatch(linha -> linha.contains("aplicacao=\"metricas-e-alerta\""));
        assertThat(doProjeto).allMatch(linha -> linha.contains("ambiente="));
    }

    private String raspar() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
    }

    static List<String> seriesQueComecamCom(String raspagem, String prefixo) {
        return Arrays.stream(raspagem.split("\n"))
                .map(String::trim)
                .filter(linha -> !linha.startsWith("#"))
                .filter(linha -> linha.startsWith(prefixo))
                .toList();
    }
}
