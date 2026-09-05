package io.github.vmarins2005.metricas.pedidos;

import io.github.vmarins2005.metricas.instrumentacao.MedidorDePedidos;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PedidosController {

    private final ConsultaDePedidos consulta;
    private final MedidorDePedidos medidor;

    PedidosController(ConsultaDePedidos consulta, MedidorDePedidos medidor) {
        this.consulta = consulta;
        this.medidor = medidor;
    }

    @GetMapping("/pedidos/{id}")
    Pedido porId(@PathVariable String id) {
        return consulta.porId(id);
    }

    /**
     * A mesma consulta, instrumentada com o id na tag.
     *
     * <p>Existe para o teste medir a explosão de séries. Ver ADR 0003.
     */
    @GetMapping("/pedidos/{id}/com-tag-de-id")
    Pedido porIdComTagRuim(@PathVariable String id) {
        long inicio = System.nanoTime();
        var pedido = consulta.porId(id);
        medidor.medirComTagDeAltaCardinalidade(id, Duration.ofNanos(System.nanoTime() - inicio));
        return pedido;
    }

    /** O interruptor que o script de alerta usa para provocar a condição. */
    @PostMapping("/caos/falha")
    String definirFalha(@RequestParam int porcentagem) {
        consulta.definirPorcentagemDeFalha(porcentagem);
        return "porcentagem de falha agora é " + porcentagem;
    }

    @ExceptionHandler(ParceiroForaException.class)
    ProblemDetail parceiroFora(ParceiroForaException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
