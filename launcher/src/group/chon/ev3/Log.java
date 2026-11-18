package group.chon.ev3;

/**
 * Log do proprio launcher
 *
 * A configuração de logging do Jason só passa a valer depois que o RunLocalMAS inicializa, 
 * e mensagens emitidas antes disso se perdem.

 * E escrever direto em System.out e System.err garante que as mensagens do launcher 
 * aparecam sempre, independentemente do que o Jason faça com o logging.
 */
final class Log {

    private static final String PREFIXO = "[chonos-ev3] ";

    private Log() {
    }

    static void info(String msg) {
        System.out.println(PREFIXO + msg);
        System.out.flush();
    }

    static void erro(String msg, Throwable t) {
        System.err.println(PREFIXO + "ERRO " + msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
        System.err.flush();
    }
}
