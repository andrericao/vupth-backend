package com.ospaulos.vupth.domain.testrun.exception;

/** Erro das rodadas de teste do painel (modo-piloto-sem-cobranca): ja ha teste ativo na loja, ou o teste ja acabou. */
public class TestRunException extends RuntimeException {

    private final String code;

    public TestRunException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
