package group.chon.ev3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jason.runtime.RuntimeServices;
import jason.runtime.RuntimeServicesFactory;
import jason.runtime.Settings;

/**
 * Recarrega os agentes do MAS sem reiniciar a máquina virtual.
 */
final class MasReloader {

    /** Diretório onde estao o .mas2j e os .asl. */
    private final Path masDir;

    /** Arquivo .mas2j de referência. */
    private final Path mas2j;

    MasReloader(Path mas2j) {
        this.mas2j = mas2j;
        Path pai = mas2j.getParent();
        this.masDir = (pai != null) ? pai : Path.of(".");
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    String status() {
        RuntimeServices rs = RuntimeServicesFactory.get();
        if (rs == null) {
            return "erro runtime indisponivel";
        }
        Collection<String> nomes = rs.getAgentsNames();
        return "ok rodando=" + rs.isRunning() + " agentes=" + nomes;
    }

    /** Tempo máximo de espera pela morte de um agente, em milissegundos. */
    private static final long TIMEOUT_KILL_MS = 15_000;

    /** Intervalo entre verificações de que o agente sumiu. */
    private static final long INTERVALO_MS = 100;

    /** Mensagem devolvida pelo Jason quando o MAS ainda não está no ar. */
    private static final String NAO_RODANDO = "system.not.running";

    /**
     * Indica se o MAS já terminou de subir e aceita operações de ciclo de vida.
     */
    boolean pronto() {
        RuntimeServices rs = RuntimeServicesFactory.get();
        return rs != null && rs.isRunning();
    }

    /**
     * Mata e recria cada agente declarado no .mas2j, a partir dos .asl
     * atualmente no disco.
     *
     * @return quantidade de agentes recarregados
     */
    int recarregar() throws Exception {
        RuntimeServices rs = RuntimeServicesFactory.get();
        if (rs == null) {
            throw new IllegalStateException("RuntimeServices indisponivel");
        }
        if (!rs.isRunning()) {
            throw new IllegalStateException("MAS ainda nao iniciado");
        }

        Map<String, String> declarados = lerAgentesDoMas2j();
        if (declarados.isEmpty()) {
            throw new IllegalStateException("nenhum agente declarado em " + mas2j);
        }

        Log.info("recarga: declarados=" + declarados.keySet()
                + " vivos=" + rs.getAgentsNames());

        int n = 0;
        for (Map.Entry<String, String> e : declarados.entrySet()) {
            String nome = e.getKey();
            String archClass = e.getValue();
            Path fonte = localizarFonte(nome);

            if (fonte == null) {
                Log.erro("fonte .asl nao encontrada para '" + nome
                        + "'. Procurado em: " + diretoriosDeFonte(), null);
                continue;
            }

            matarVariantes(rs, nome);

            List<String> archs = (archClass == null) ? null : List.of(archClass);

            String criado = rs.createAgent(
                    nome,                    // nome do agente
                    fonte.toString(),        // fonte .asl
                    null,                    // classe do agente (padrao)
                    archs,                   // arquiteturas (Argo, etc.)
                    null,                    // parametros da base de crencas
                    new Settings(),          // configuracoes
                    null);                   // agente criador

            if (criado == null || NAO_RODANDO.equals(criado)) {
                throw new IllegalStateException(
                        "createAgent recusou '" + nome + "': " + criado);
            }

            if (!nome.equals(criado)) {
                Log.erro("nome renomeado: pedido '" + nome + "', criado '"
                        + criado + "' - possivel agente orfao", null);
            }

            rs.startAgent(criado);
            Log.info("create '" + criado + "' arch=" + archClass);
            n++;
        }

        if (n == 0) {
            // Sem esta guarda, uma recarga que nao recarregou nada responderia
            // "ok 0 agentes" ao canal de controle, o script casaria com o
            // padrao 'ok*', declararia sucesso e sairia com codigo 0 - um
            // deploy que nao aconteceu, reportado como concluido. 
            throw new IllegalStateException(
                    "nenhum agente recarregado; fontes .asl nao encontradas em "
                    + diretoriosDeFonte());
        }

        Log.info("recarga concluida: vivos=" + rs.getAgentsNames());
        return n;
    }

    /**
     * Localiza o arquivo fonte de um agente, tentando os diretorios possiveis.
     *
     * @return o caminho, ou null se nao encontrado em nenhum deles
     */
    private Path localizarFonte(String nome) throws IOException {
        for (Path d : diretoriosDeFonte()) {
            Path p = d.resolve(nome + ".asl");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Diretorios onde procurar os arquivos {@code .asl}, em ordem de
     * preferencia.
     */
    private List<Path> diretoriosDeFonte() throws IOException {
        List<Path> dirs = new ArrayList<>();

        String texto = Files.readString(mas2j, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");

        int i = texto.indexOf("aslSourcePath:");
        if (i >= 0) {
            String bloco = texto.substring(i + "aslSourcePath:".length());
            for (String fim : new String[]{";", "}"}) {
                int p = bloco.indexOf(fim);
                if (p >= 0) {
                    bloco = bloco.substring(0, p);
                }
            }
            Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(bloco);
            while (m.find()) {
                dirs.add(masDir.resolve(m.group(1)));
            }
        }

        dirs.add(masDir);
        dirs.add(masDir.resolve("src/asl"));
        dirs.add(masDir.resolve("asl"));
        return dirs;
    }

    /**
     * Mata o agente de nome informado e quaisquer variantes renomeadas
     * (nome_1, nome_2, ...), aguardando a confirmação de que
     * cada um desapareceu da lista de agentes vivos.
     */
    private void matarVariantes(RuntimeServices rs, String nome)
            throws InterruptedException {

        Pattern variante = Pattern.compile(Pattern.quote(nome) + "(_\\d+)?");

        for (String vivo : new ArrayList<>(rs.getAgentsNames())) {
            if (!variante.matcher(vivo).matches()) {
                continue;
            }

            boolean pedido = rs.killAgent(vivo, "chonos-launcher", 1);
            boolean morreu = aguardarMorte(rs, vivo);
            Log.info("kill '" + vivo + "' -> pedido=" + pedido + " confirmado=" + morreu);

            if (!morreu) {
                Log.erro("agente '" + vivo + "' nao morreu em "
                        + TIMEOUT_KILL_MS + " ms", null);
            }
        }
    }

    /** Aguarda ate o agente sumir da lista de vivos, ou ate estourar o tempo. */
    private boolean aguardarMorte(RuntimeServices rs, String nome)
            throws InterruptedException {

        long limite = System.currentTimeMillis() + TIMEOUT_KILL_MS;
        while (System.currentTimeMillis() < limite) {
            if (!rs.getAgentsNames().contains(nome)) {
                return true;
            }
            Thread.sleep(INTERVALO_MS);
        }
        return false;
    }

    void encerrar() throws Exception {
        RuntimeServices rs = RuntimeServicesFactory.get();
        if (rs != null) {
            rs.stopMAS();
        }
    }

    // ------------------------------------------------------------------
    // Parser mínimo de .mas2j
    // ------------------------------------------------------------------

    /**
     * Extrai o bloco "agents" do .mas2j.
     *
     * MAS nome {
     *     agents:
     *         rover agentArchClass group.chon.agent.argo.Argo;
     *         ...;
     * }
     *
     * @return mapa nome do agente para classe de arquitetura, ou null quando
     *         nao declarada
     */
    private Map<String, String> lerAgentesDoMas2j() throws IOException {
        String texto = Files.readString(mas2j, StandardCharsets.UTF_8);

        // remove comentários de bloco e de linha
        texto = texto.replaceAll("(?s)/\\*.*?\\*/", " ");
        texto = texto.replaceAll("//[^\n]*", " ");

        int ini = texto.indexOf("agents:");
        if (ini < 0) {
            return Map.of();
        }
        texto = texto.substring(ini + "agents:".length());

        // o bloco termina na proxima diretiva conhecida ou no fecha-chaves
        for (String fim : new String[]{
                "environment:", "aslSourcePath:", "classpath:",
                "infrastructure:", "directives:", "}"}) {
            int p = texto.indexOf(fim);
            if (p >= 0) {
                texto = texto.substring(0, p);
            }
        }

        Map<String, String> agentes = new LinkedHashMap<>();
        for (String decl : texto.split(";")) {
            String[] tk = decl.trim().split("\\s+");
            if (tk.length == 0 || tk[0].isEmpty()) {
                continue;
            }
            String nome = tk[0];
            String arch = null;
            for (int i = 1; i < tk.length - 1; i++) {
                if ("agentArchClass".equals(tk[i])) {
                    arch = tk[i + 1];
                    break;
                }
            }
            agentes.put(nome, arch);
        }
        return agentes;
    }
}