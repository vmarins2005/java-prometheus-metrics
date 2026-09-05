package io.github.vmarins2005.metricas.pedidos;

public class PedidoNaoEncontradoException extends RuntimeException {

    public PedidoNaoEncontradoException(String id) {
        super("pedido " + id + " não encontrado");
    }
}
