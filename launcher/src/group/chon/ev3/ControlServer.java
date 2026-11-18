package group.chon.ev3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Canal de controle do launcher: um socket em loopback que recebe comandos de
 * linha única.
 *
 * Escuta apenas em 127.0.0.1. O comando chega pelo próprio dispositivo, via
 * o script chonosEmbeddedMAS, nunca da rede.
 *
 * Comandos aceitos:
 *   ping - devolve "pong"; usado para saber se ha runtime vivo
 *   status - lista os agentes em execução
 *   reload - recarrega os agentes a partir dos .asl no disco
 *   stop - encerra o MAS
 */
final class ControlServer implements Runnable {

    static final int PORTA_PADRAO = 3276;

    private final int porta;
    private final MasReloader reloader;
    private volatile boolean ativo = true;

    ControlServer(int porta, MasReloader reloader) {
        this.porta = porta;
        this.reloader = reloader;
    }

    /** Sobe o servidor numa thread daemon, para nao impedir o fim da JVM. */
    static ControlServer iniciar(int porta, MasReloader reloader) {
        ControlServer s = new ControlServer(porta, reloader);
        Thread t = new Thread(s, "chonos-control");
        t.setDaemon(true);
        t.start();
        return s;
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(
                porta, 4, InetAddress.getLoopbackAddress())) {

            Log.info("canal de controle em 127.0.0.1:" + porta);

            while (ativo) {
                try (Socket cliente = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             cliente.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(
                             cliente.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String linha = in.readLine();
                    if (linha == null) {
                        continue;
                    }
                    out.println(atender(linha.trim()));

                } catch (IOException e) {
                    Log.erro("falha atendendo comando de controle", e);
                }
            }
        } catch (IOException e) {
            Log.erro("nao foi possivel abrir o canal de controle na porta " + porta, e);
        }
    }

    private String atender(String comando) {
        Log.info("comando recebido: " + comando);
        try {
            switch (comando) {
                case "ping":
                    // Distingue "canal disponível" de "MAS pronto". O canal sobe
                    // antes do MAS, entao existe uma janela em que responde
                    // sem haver o que controlar
                    return reloader.pronto() ? "ok pronto" : "ok iniciando";

                case "status":
                    return reloader.status();

                case "reload":
                    long t0 = System.currentTimeMillis();
                    int n = reloader.recarregar();
                    long ms = System.currentTimeMillis() - t0;
                    return "ok " + n + " agentes recarregados em " + ms + " ms";

                case "stop":
                    ativo = false;
                    reloader.encerrar();
                    return "ok encerrando";

                default:
                    return "erro comando desconhecido: " + comando;
            }
        } catch (Exception e) {
            Log.erro("erro executando '" + comando + "'", e);
            return "erro " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
