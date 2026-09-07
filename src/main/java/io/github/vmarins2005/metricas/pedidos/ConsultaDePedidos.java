package io.github.vmarins2005.metricas.pedidos;

import io.github.vmarins2005.metricas.instrumentacao.MedidorDePedidos;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * O serviço que se quer observar.
 *
 * <p>A latência é sorteada com a forma que latência de serviço tem: quase tudo rápido, e uma
 * cauda longa. É essa forma que faz a média mentir.
 *
 * <p>A taxa de erro é controlável para que o teste e o script de alerta possam provocar a
 * condição exata que dispara o alarme.
 */
@Service
public class ConsultaDePedidos {

    private final MedidorDePedidos medidor;

    /** Quantas das próximas cem consultas devem falhar. Zero em operação normal. */
    private final AtomicInteger porcentagemDeFalha = new AtomicInteger(0);

    ConsultaDePedidos(MedidorDePedidos medidor) {
        this.medidor = medidor;
    }

    public void definirPorcentagemDeFalha(int porcentagem) {
        porcentagemDeFalha.set(porcentagem);
    }

    public Pedido porId(String id) {
        var amostra = medidor.comecar();
        medidor.comecouUmaConsulta();
        try {
            dormeComACaudaDeSempre();
            if (ThreadLocalRandom.current().nextInt(100) < porcentagemDeFalha.get()) {
                throw new ParceiroForaException();
            }
            var pedido = new Pedido(id, "CONFIRMADO", 9_990);
            medidor.encerrar(amostra, "sucesso");
            return pedido;
        } catch (ParceiroForaException e) {
            medidor.encerrar(amostra, "erro");
            throw e;
        } finally {
            medidor.terminouUmaConsulta();
        }
    }

    /**
     * Log-normal com mediana de 20 ms — a mesma forma usada no `java-resilience4j`
     * desta série: a maioria das chamadas rápida, e uma cauda que vai longe.
     *
     * <p>Se a latência fosse constante, média e p99 seriam iguais e este repositório não teria
     * assunto.
     */
    private void dormeComACaudaDeSempre() {
        double milissegundos =
                Math.exp(Math.log(20) + ThreadLocalRandom.current().nextGaussian() * 0.9);
        try {
            Thread.sleep((long) Math.max(1, Math.min(2_000, milissegundos)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Alimenta o medidor com uma duração conhecida, sem dormir — para os testes. */
    public void medirDuracaoConhecida(Duration duracao, String resultado) {
        var amostra = medidor.comecar();
        medidor.encerrar(amostra, resultado);
        medidor.registrarSemHistograma(duracao);
    }
}
