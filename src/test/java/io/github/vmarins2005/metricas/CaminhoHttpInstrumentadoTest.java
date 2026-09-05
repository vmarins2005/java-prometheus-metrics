package io.github.vmarins2005.metricas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O caminho de verdade: requisição HTTP, serviço, instrumentação, raspagem.
 *
 * <p>Os outros testes alimentam o medidor diretamente, porque medir distribuição exige
 * durações exatas. Este passa pelo controlador, e é o que garante que a instrumentação está
 * no caminho que as requisições realmente percorrem — e não só numa classe que os testes
 * chamam.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaminhoHttpInstrumentadoTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("uma consulta bem-sucedida conta como sucesso e aparece no histograma")
    void umaConsultaBemSucedida() throws Exception {
        double antes = contagemDe("sucesso");

        mockMvc.perform(get("/pedidos/{id}", "PED-1"))
                .andExpect(status().isOk());

        System.out.printf("consultas com sucesso: %.0f → %.0f%n", antes, contagemDe("sucesso"));
        assertThat(contagemDe("sucesso")).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("a falha provocada vira 503 e conta como erro")
    void aFalhaProvocadaViraErro() throws Exception {
        mockMvc.perform(post("/caos/falha").param("porcentagem", "100"))
                .andExpect(status().isOk());
        try {
            double antes = contagemDe("erro");

            mockMvc.perform(get("/pedidos/{id}", "PED-2"))
                    .andExpect(status().isServiceUnavailable());

            System.out.printf("consultas com erro: %.0f → %.0f%n", antes, contagemDe("erro"));

            // O contador do desfecho é incrementado no `catch`, e não só no caminho feliz.
            // Uma instrumentação que só mede o sucesso produz uma taxa de erro sempre zero.
            assertThat(contagemDe("erro")).isEqualTo(antes + 1);
        } finally {
            mockMvc.perform(post("/caos/falha").param("porcentagem", "0"));
        }
    }

    @Test
    @DisplayName("a consulta com o id na tag cria série própria — e continua respondendo igual")
    void aConsultaComIdNaTag() throws Exception {
        mockMvc.perform(get("/pedidos/{id}/com-tag-de-id", "PED-3"))
                .andExpect(status().isOk());

        // A rota errada de propósito devolve exatamente a mesma coisa que a certa. É por isso
        // que o problema não aparece em teste funcional nenhum. Ver ADR 0003.
        assertThat(RaspagemDoPrometheusTest.seriesQueComecamCom(raspar(), "pedidos_consulta_por_id"))
                .anyMatch(linha -> linha.contains("pedido=\"PED-3\""));
    }

    @Test
    @DisplayName("o gauge de consultas em andamento existe e volta a zero")
    void oGaugeDeConsultasEmAndamento() throws Exception {
        mockMvc.perform(get("/pedidos/{id}", "PED-4")).andExpect(status().isOk());

        var gauge = RaspagemDoPrometheusTest
                .seriesQueComecamCom(raspar(), "pedidos_consultas_em_andamento");
        System.out.printf("gauge: %s%n", gauge.getFirst());

        // Zero depois da requisição terminar. Um contador ficaria em 1 para sempre, e o
        // gráfico responderia a outra pergunta que ninguém fez.
        assertThat(gauge).hasSize(1);
        assertThat(gauge.getFirst()).endsWith(" 0.0");
    }

    private double contagemDe(String resultado) throws Exception {
        return RaspagemDoPrometheusTest
                .seriesQueComecamCom(raspar(), "pedidos_consulta_resultado_total").stream()
                .filter(linha -> linha.contains("resultado=\"" + resultado + "\""))
                .findFirst()
                .map(linha -> Double.parseDouble(linha.substring(linha.lastIndexOf(' ') + 1)))
                .orElse(0.0);
    }

    private String raspar() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
    }
}
