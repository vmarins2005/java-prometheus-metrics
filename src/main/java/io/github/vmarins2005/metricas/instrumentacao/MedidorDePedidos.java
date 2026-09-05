package io.github.vmarins2005.metricas.instrumentacao;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * As métricas do serviço, declaradas num lugar só.
 *
 * <p>Cada decisão aqui tem uma consequência medida em teste, e todas elas são invisíveis
 * quando a instrumentação é uma anotação {@code @Timed} espalhada pelos controladores.
 */
@Component
public class MedidorDePedidos {

    /** O compromisso com quem chama. Vira anotação no gráfico e limiar no alerta. */
    public static final Duration SLO = Duration.ofMillis(300);

    private final MeterRegistry registro;
    private final Timer comHistograma;
    private final Timer semHistograma;
    private final AtomicInteger emAndamento = new AtomicInteger();

    public MedidorDePedidos(MeterRegistry registro) {
        this.registro = registro;

        /*
         * Com histograma: publica os baldes que o Prometheus precisa para calcular percentil.
         *
         * Sem `publishPercentileHistogram`, um Timer do Micrometer publica apenas contagem,
         * soma e máximo. Dá para calcular a média — e a média mente. Ver ADR 0001.
         *
         * `serviceLevelObjectives` acrescenta um balde exatamente no limiar do SLO, e é ele
         * que permite escrever a consulta "fração de requisições dentro do SLO" sem
         * interpolar entre baldes.
         */
        this.comHistograma = Timer.builder("pedidos.consulta")
                .description("Tempo de consulta de um pedido")
                .publishPercentileHistogram()
                .serviceLevelObjectives(SLO)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registro);

        // A mesma medição, sem histograma. Existe para o teste mostrar o que falta.
        this.semHistograma = Timer.builder("pedidos.consulta.sem.histograma")
                .description("A mesma coisa, sem os baldes — não dá percentil")
                .register(registro);

        /*
         * Consultas em andamento: um `Gauge` é o instrumento certo para algo que sobe e desce.
         *
         * Contador não serve, e a distinção não é acadêmica: contador só cresce, e "quantas
         * consultas estão em andamento agora" não é uma contagem acumulada. Publicar isso como
         * contador daria um gráfico que só sobe e nunca responde à pergunta.
         */
        registro.gauge("pedidos.consultas.em.andamento", emAndamento);
    }

    public Timer.Sample comecar() {
        return Timer.start(registro);
    }

    /**
     * Encerra a medição e conta o resultado.
     *
     * <p>O contador tem a tag {@code resultado}, e é isso que permite escrever uma <b>taxa</b>
     * de erro em vez de uma contagem. "10 erros" não diz nada: dez de dez é catástrofe, dez
     * de dez milhões é terça-feira. Ver ADR 0002.
     */
    public void encerrar(Timer.Sample amostra, String resultado) {
        amostra.stop(comHistograma);
        Counter.builder("pedidos.consulta.resultado")
                .description("Consultas por desfecho")
                .tag("resultado", resultado)
                .register(registro)
                .increment();
    }

    public void registrarSemHistograma(Duration duracao) {
        semHistograma.record(duracao);
    }

    /**
     * Registra uma duração exata, sem cronometrar.
     *
     * <p>Existe para que um teste possa alimentar uma distribuição conhecida e comparar o que
     * a média diz com o que o percentil diz. Cronometrar de verdade daria números diferentes
     * a cada execução e a comparação perderia o sentido.
     */
    public void registrar(Duration duracao, String resultado) {
        comHistograma.record(duracao);
        Counter.builder("pedidos.consulta.resultado")
                .tag("resultado", resultado)
                .register(registro)
                .increment();
    }

    public void comecouUmaConsulta() {
        emAndamento.incrementAndGet();
    }

    public void terminouUmaConsulta() {
        emAndamento.decrementAndGet();
    }

    /**
     * A instrumentação errada de propósito: uma tag com o id do pedido.
     *
     * <p>Parece útil — "quero ver o tempo por pedido" — e cria <b>uma série temporal por
     * pedido</b>. Cada série ocupa memória no Prometheus, para sempre, e não some quando o
     * pedido deixa de existir.
     *
     * <p>Com histograma junto, cada série vira dezenas de séries, uma por balde. É o modo mais
     * rápido de derrubar um Prometheus, e o teste mede o tamanho do estrago. Ver ADR 0003.
     */
    public void medirComTagDeAltaCardinalidade(String idDoPedido, Duration duracao) {
        Timer.builder("pedidos.consulta.por.id")
                .tag("pedido", idDoPedido)
                .register(registro)
                .record(duracao);
    }
}
