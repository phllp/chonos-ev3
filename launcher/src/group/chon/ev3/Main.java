package group.chon.ev3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Lançador do sistema multiagente para ev3dev / LEGO Mindstorms EV3.
 */
public final class Main {

    private static final String VERSAO = "chonos-ev3 launcher 0.2";

    private static final String RUN_LOCAL_MAS = "jason.infra.local.RunLocalMAS";

    private static final String LOGCONF_PADRAO =
            System.getProperty("chonos.logconf",
                    System.getProperty("user.home") + "/chonos/logging.properties");

    private static final int PORTA_CONTROLE = Integer.getInteger(
            "chonos.control.port", ControlServer.PORTA_PADRAO);

    private Main() {
    }

    /**
     * Lança o MAS. 
     * 
     * Pode subir em modo servidor (padrão) ou cliente (--control). O modo cliente
     * envia um comando ao runtime residente e encerra. O modo servidor sobe o MAS
     * e aguarda comandos de controle.
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        if (args.length == 0) {
            System.err.println(VERSAO);
            System.err.println("uso: <arquivo.mas2j> [opcoes do Jason]");
            System.err.println("     --control <ping|status|reload|stop>");
            System.exit(1);
        }

        if ("--control".equals(args[0])) {
            String cmd = (args.length > 1) ? args[1] : "ping";
            System.exit(cliente(cmd) ? 0 : 1);
        }

        servidor(args);
    }

    // ------------------------------------------------------------------
    // Modo servidor
    // ------------------------------------------------------------------

    /**
     * Recebe o caminho do .mas2j e faz quatro coisas: 
     *   - força o modo headless;
     *   - injeta o --log-conf;
     *   - levanta o canal de controle numa thread separada (daemon); 
     *   - entrega o controle ao RunLocalMAS do Jason que cria os agentes;
    */
    private static void servidor(String[] args) throws Exception {
        Log.info(VERSAO);

        List<String> repassados = new ArrayList<>(List.of(args));

        // Injeta --log-conf se ausente. Sem isso o Jason cai no handler
        // grafico e o log desaparece por completo.
        if (!repassados.contains("--log-conf")) {
            Path conf = garantirLogConf();
            repassados.add("--log-conf");
            repassados.add(conf.toString());
            Log.info("log-conf: " + conf);
        }

        Path mas2j = Paths.get(args[0]).toAbsolutePath();
        MasReloader reloader = new MasReloader(mas2j);

        // O canal de controle sobe numa thread daemon ANTES do MAS. Assim, se
        // a inicialização do MAS demorar - e neste hardware demora mais de um
        // minuto -, ja e possivel consultar o status durante a subida.
        ControlServer.iniciar(PORTA_CONTROLE, reloader);

        delegar(repassados);
    }

    /**
     * Chama o RunLocalMAS por reflexão para que o lançador não dependa
     * do nome da classe em tempo de compilação
     */
    private static void delegar(List<String> args) throws Exception {
        Class<?> alvo;
        try {
            alvo = Class.forName(RUN_LOCAL_MAS);
        } catch (ClassNotFoundException e) {
            Log.erro(RUN_LOCAL_MAS + " nao encontrada no classpath", null);
            System.exit(2);
            return;
        }
        alvo.getMethod("main", String[].class)
            .invoke(null, (Object) args.toArray(new String[0]));
    }

    // ------------------------------------------------------------------
    // Modo cliente
    // ------------------------------------------------------------------

    /**
     * Envia um comando ao runtime residente. Usado pelo script
     * chonosEmbeddedMAS, permite obter informações sobre o runtime pelo terminal, 
     * sem abrir a ChonIDE. 
     * 
     * Comandos aceitos: ping, status, reload, stop.
     */
    private static boolean cliente(String comando) {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), PORTA_CONTROLE)) {
            s.setSoTimeout(60_000);
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(
                    s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));

            out.println(comando);
            String resposta = in.readLine();
            System.out.println(resposta == null ? "sem resposta" : resposta);
            return resposta != null && resposta.startsWith("ok");

        } catch (IOException e) {
            System.out.println("sem runtime em 127.0.0.1:" + PORTA_CONTROLE);
            return false;
        }
    }

    // ------------------------------------------------------------------

    private static Path garantirLogConf() throws IOException {
        Path conf = Paths.get(LOGCONF_PADRAO);
        if (Files.isRegularFile(conf)) {
            return conf;
        }

        Path pai = conf.getParent();
        if (pai != null) {
            Files.createDirectories(pai);
        }

        try (InputStream in = Main.class.getResourceAsStream("/logging.properties")) {
            if (in != null) {
                Files.copy(in, conf, StandardCopyOption.REPLACE_EXISTING);
                return conf;
            }
        }

        // O %6$s no formato é essencial: sem ele, exceções são descartadas na
        // formatacção e erros aparecem como uma linha solta, sem causa.
        String padrao =
                "handlers = java.util.logging.ConsoleHandler\n"
              + ".level = INFO\n"
              + "java.util.logging.ConsoleHandler.level = INFO\n"
              + "java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter\n"
              + "java.util.logging.SimpleFormatter.format = [%4$s] %5$s%6$s%n\n"
              + "jason.level = INFO\n";

        try (OutputStream out = Files.newOutputStream(conf)) {
            out.write(padrao.getBytes(StandardCharsets.UTF_8));
        }
        return conf;
    }
}
