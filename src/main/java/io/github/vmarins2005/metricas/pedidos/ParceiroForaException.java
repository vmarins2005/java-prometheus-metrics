package io.github.vmarins2005.metricas.pedidos;

public class ParceiroForaException extends RuntimeException {

    public ParceiroForaException() {
        super("parceiro indisponível");
    }
}
