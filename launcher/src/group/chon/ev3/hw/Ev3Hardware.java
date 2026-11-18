package group.chon.ev3.hw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Acesso a motores, sensores, LEDs, alto-falante e bateria do LEGO Mindstorms
 * EV3 pelo sysfs do ev3dev.
 */
public final class Ev3Hardware {

    /**
     * Raiz do sysfs
     */
    private static final Path RAIZ = Path.of(System.getProperty("ev3.sysfs.raiz", "/"));

    private static final Path DIR_MOTORES = RAIZ.resolve("sys/class/tacho-motor");
    private static final Path DIR_SENSORES = RAIZ.resolve("sys/class/lego-sensor");
    private static final Path DIR_LEDS = RAIZ.resolve("sys/class/leds");
    private static final Path DIR_BATERIA = RAIZ.resolve("sys/class/power_supply");
    private static final Path DIR_PLATAFORMA = RAIZ.resolve("sys/devices/platform");

    // ------------------------------------------------------------------
    // Drivers conhecidos
    // ------------------------------------------------------------------

    public static final String ULTRASSONICO = "ultrasonic";
    public static final String GIROSCOPIO = "gyro";
    public static final String TOQUE = "touch";
    public static final String COR = "color";

    private static final Map<String, List<String>> DRIVERS = new LinkedHashMap<>();
    static {
        DRIVERS.put(ULTRASSONICO, Arrays.asList("lego-ev3-us", "lego-nxt-us"));
        DRIVERS.put(GIROSCOPIO, Arrays.asList("lego-ev3-gyro"));
        DRIVERS.put(TOQUE, Arrays.asList("lego-ev3-touch", "lego-nxt-touch"));
        DRIVERS.put(COR, Arrays.asList("lego-ev3-color", "lego-nxt-light"));
    }

    /** Driver do motor grande, usado para validar a montagem do chassi. */
    private static final String DRIVER_MOTOR_GRANDE = "lego-ev3-l-motor";
    /** Driver do motor medio. */
    private static final String DRIVER_MOTOR_MEDIO = "lego-ev3-m-motor";

    // ------------------------------------------------------------------
    // Estado descoberto no construtor
    // ------------------------------------------------------------------

    /** Endereco fisico para diretorio, ex.: "ev3-ports:outA" -> motor0. */
    private final Map<String, Path> motores = new LinkedHashMap<>();
    /** Endereco fisico para diretorio, ex.: "ev3-ports:in1" -> sensor0. */
    private final Map<String, Path> sensores = new LinkedHashMap<>();
    /** Familia de sensor para diretorio, ex.: "ultrasonic" -> sensor0. */
    private final Map<String, Path> porFamilia = new LinkedHashMap<>();

    /** "esquerdo:vermelho" -> arquivo brightness. */
    private final Map<String, Path> leds = new LinkedHashMap<>();
    private int brilhoMaximo = 255;

    private Path arquivoTom;
    private Path bateria;

    public Ev3Hardware() throws IOException {
        mapearPorEndereco(DIR_MOTORES, motores);
        mapearPorEndereco(DIR_SENSORES, sensores);
        mapearPorFamilia();
        mapearLeds();
        localizarTom();
        localizarBateria();
    }

    private static void listarOrdenado(Path base, List<Path> destino) throws IOException {
        if (!Files.isDirectory(base)) {
            return;
        }
        // Collectors.toList() em vez de Stream.toList(): este ultimo so existe
        // a partir do Java 16, e o alvo aqui e bytecode 11.
        try (Stream<Path> fluxo = Files.list(base)) {
            destino.addAll(fluxo.sorted().collect(Collectors.toList()));
        }
    }

    private static void mapearPorEndereco(Path base, Map<String, Path> destino)
            throws IOException {
        List<Path> entradas = new ArrayList<>();
        listarOrdenado(base, entradas);
        for (Path p : entradas) {
            Path addr = p.resolve("address");
            if (Files.isReadable(addr)) {
                destino.put(ler(addr), p);
            }
        }
    }

    /**
     * Indexa os sensores presentes pela familia a que pertencem, lendo "driver_name" de cada um.
     *
     * Se houver dois sensores da mesma familia, o de menor porta ganha
     */
    private void mapearPorFamilia() throws IOException {
        for (Map.Entry<String, Path> e : sensores.entrySet()) {
            Path dn = e.getValue().resolve("driver_name");
            if (!Files.isReadable(dn)) {
                continue;
            }
            String driver = ler(dn);
            for (Map.Entry<String, List<String>> fam : DRIVERS.entrySet()) {
                if (fam.getValue().contains(driver)) {
                    porFamilia.putIfAbsent(fam.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * Localiza os quatro LEDs do bloco (dois lados, duas cores cada).
     */
    private void mapearLeds() throws IOException {
        List<Path> entradas = new ArrayList<>();
        listarOrdenado(DIR_LEDS, entradas);
        for (Path p : entradas) {
            String nome = p.getFileName().toString().toLowerCase();
            String lado = null;
            if (nome.contains("led0") || nome.contains("left")) {
                lado = "esquerdo";
            } else if (nome.contains("led1") || nome.contains("right")) {
                lado = "direito";
            }
            String cor = null;
            if (nome.contains("red")) {
                cor = "vermelho";
            } else if (nome.contains("green")) {
                cor = "verde";
            }
            if (lado == null || cor == null) {
                continue;
            }
            Path brilho = p.resolve("brightness");
            if (!Files.isWritable(brilho)) {
                continue;
            }
            leds.put(lado + ":" + cor, brilho);

            Path max = p.resolve("max_brightness");
            if (Files.isReadable(max)) {
                try {
                    brilhoMaximo = Integer.parseInt(ler(max));
                } catch (NumberFormatException ignored) {
                    // mantem o padrao de 255
                }
            }
        }
    }

    /**
     * Procura o atributo de sysfs que gera tom no alto-falante.
     *
     * O driver de som do EV3 expoe um arquivo "tone" em que se escreve
     * a frequência, opcionalmente seguida da duração em milissegundos
     */
    private void localizarTom() throws IOException {
        Path direto = DIR_PLATAFORMA.resolve("snd-legoev3/tone");
        if (Files.isWritable(direto)) {
            arquivoTom = direto;
            return;
        }
        List<Path> entradas = new ArrayList<>();
        listarOrdenado(DIR_PLATAFORMA, entradas);
        for (Path p : entradas) {
            Path t = p.resolve("tone");
            if (Files.isWritable(t)) {
                arquivoTom = t;
                return;
            }
        }
    }

    private void localizarBateria() throws IOException {
        List<Path> entradas = new ArrayList<>();
        listarOrdenado(DIR_BATERIA, entradas);
        for (Path p : entradas) {
            if (Files.isReadable(p.resolve("voltage_now"))) {
                bateria = p;
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Inspecao
    // ------------------------------------------------------------------

    public List<String> enderecosDeMotores() {
        return new ArrayList<>(motores.keySet());
    }

    public List<String> enderecosDeSensores() {
        return new ArrayList<>(sensores.keySet());
    }

    /** Familias de sensor efetivamente presentes, ex.: [ultrasonic, touch]. */
    public List<String> familiasDeSensores() {
        return new ArrayList<>(porFamilia.keySet());
    }

    public boolean temFamilia(String familia) {
        return porFamilia.containsKey(familia);
    }

    public boolean temMotor(String endereco) {
        return motores.containsKey(normalizar(endereco));
    }

    public boolean temLeds() {
        return !leds.isEmpty();
    }

    public boolean temAltoFalante() {
        return arquivoTom != null || beepDisponivel();
    }

    public boolean temBateria() {
        return bateria != null;
    }

    /** Caminhos encontrados, para o diagnostico imprimir o que foi detectado. */
    public Map<String, String> inventario() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : motores.entrySet()) {
            m.put("motor " + e.getKey(), e.getValue().toString());
        }
        for (Map.Entry<String, Path> e : porFamilia.entrySet()) {
            m.put("sensor " + e.getKey(), e.getValue().toString());
        }
        for (Map.Entry<String, Path> e : leds.entrySet()) {
            m.put("led " + e.getKey(), e.getValue().toString());
        }
        m.put("tom", arquivoTom != null ? arquivoTom.toString()
                : (beepDisponivel() ? "utilitario beep" : "nao encontrado"));
        m.put("bateria", bateria != null ? bateria.toString() : "nao encontrado");
        m.put("brilho maximo do led", Integer.toString(brilhoMaximo));
        return m;
    }

    /** Aceita "outA" ou "ev3-ports:outA". */
    private Path motor(String endereco) {
        Path p = motores.get(normalizar(endereco));
        if (p == null) {
            throw new IllegalArgumentException(
                    "motor nao encontrado em '" + endereco + "'; disponiveis: "
                    + motores.keySet());
        }
        return p;
    }

    private Path sensor(String endereco) {
        Path p = sensores.get(normalizar(endereco));
        if (p == null) {
            throw new IllegalArgumentException(
                    "sensor nao encontrado em '" + endereco + "'; disponiveis: "
                    + sensores.keySet());
        }
        return p;
    }

    private Path familia(String nome) {
        Path p = porFamilia.get(nome);
        if (p == null) {
            throw new IllegalArgumentException(
                    "nenhum sensor da familia '" + nome + "' conectado; "
                    + "presentes: " + porFamilia.keySet());
        }
        return p;
    }

    private static String normalizar(String endereco) {
        return endereco.startsWith("ev3-ports:") ? endereco : "ev3-ports:" + endereco;
    }

    // ------------------------------------------------------------------
    // Motores
    // ------------------------------------------------------------------

    public String driverDeMotor(String endereco) throws IOException {
        return ler(motor(endereco).resolve("driver_name"));
    }

    public boolean motorEhGrande(String endereco) throws IOException {
        return DRIVER_MOTOR_GRANDE.equals(driverDeMotor(endereco));
    }

    public boolean motorEhMedio(String endereco) throws IOException {
        return DRIVER_MOTOR_MEDIO.equals(driverDeMotor(endereco));
    }

    public int velocidadeMaxima(String endereco) throws IOException {
        return Integer.parseInt(ler(motor(endereco).resolve("max_speed")));
    }

    /** Pulsos do encoder por volta completa do eixo. 360 nos motores do EV3. */
    public int pulsosPorVolta(String endereco) throws IOException {
        return Integer.parseInt(ler(motor(endereco).resolve("count_per_rot")));
    }

    /**
     * Define o que acontece no {@code stop}.
     *
     * @param acao {@code coast} solta o motor, {@code brake} freia,
     *             {@code hold} segura a posicao ativamente. O padrao de fabrica
     *             e {@code coast}, que faz o robo deslizar ao parar -
     *             inadequado para parar diante de obstaculo.
     */
    public void acaoDeParada(String endereco, String acao) throws IOException {
        escrever(motor(endereco).resolve("stop_action"), acao);
    }

    /** Inverte o sentido de giro. Util quando os motores sao montados espelhados. */
    public void polaridade(String endereco, boolean invertida) throws IOException {
        escrever(motor(endereco).resolve("polarity"),
                invertida ? "inversed" : "normal");
    }

    /**
     * Gira o motor indefinidamente na velocidade informada.
     *
     * @param velocidade em pulsos do encoder por segundo; o motor grande do
     *                   EV3 aceita ate 1050 e o medio ate 1560. Valor negativo
     *                   inverte o sentido.
     */
    public void girar(String endereco, int velocidade) throws IOException {
        Path m = motor(endereco);
        escrever(m.resolve("speed_sp"), Integer.toString(velocidade));
        escrever(m.resolve("command"), "run-forever");
    }

    /**
     * Gira o motor por um deslocamento relativo e para sozinho ao chegar.
     *
     * <p>O controle de posicao fica no driver do kernel, nao no agente. Um
     * {@code .argo.act} que use isto retorna imediatamente, e o agente continua
     * deliberando enquanto o motor se move - diferente de encadear
     * {@code girar} com {@code .wait}, que bloqueia o ciclo de raciocinio pelo
     * tempo da manobra.
     *
     * @param graus deslocamento em pulsos do encoder; 360 e uma volta
     */
    public void girarPor(String endereco, int graus, int velocidade) throws IOException {
        Path m = motor(endereco);
        escrever(m.resolve("speed_sp"), Integer.toString(Math.abs(velocidade)));
        escrever(m.resolve("position_sp"), Integer.toString(graus));
        escrever(m.resolve("command"), "run-to-rel-pos");
    }

    /** Gira ate a posicao absoluta do encoder e para sozinho. */
    public void girarAte(String endereco, int posicao, int velocidade) throws IOException {
        Path m = motor(endereco);
        escrever(m.resolve("speed_sp"), Integer.toString(Math.abs(velocidade)));
        escrever(m.resolve("position_sp"), Integer.toString(posicao));
        escrever(m.resolve("command"), "run-to-abs-pos");
    }

    public void parar(String endereco) throws IOException {
        escrever(motor(endereco).resolve("command"), "stop");
    }

    /** Posicao do encoder, em pulsos. O motor grande tem 360 por volta. */
    public int posicao(String endereco) throws IOException {
        return Integer.parseInt(ler(motor(endereco).resolve("position")));
    }

    public void zerarPosicao(String endereco) throws IOException {
        escrever(motor(endereco).resolve("position"), "0");
    }

    /**
     * Estado reportado pelo driver:
     * Valores possíveis: "running", "ramping", "holding", "overloaded",
     */
    public String estado(String endereco) throws IOException {
        return ler(motor(endereco).resolve("state"));
    }

    // ------------------------------------------------------------------
    // Sensores
    // ------------------------------------------------------------------

    public String driverDeSensor(String endereco) throws IOException {
        return ler(sensor(endereco).resolve("driver_name"));
    }

    public String modo(String endereco) throws IOException {
        return ler(sensor(endereco).resolve("mode"));
    }

    public void modo(String endereco, String novoModo) throws IOException {
        escrever(sensor(endereco).resolve("mode"), novoModo);
    }

    public String modosDisponiveis(String endereco) throws IOException {
        return ler(sensor(endereco).resolve("modes"));
    }

    public String unidade(String endereco) throws IOException {
        return ler(sensor(endereco).resolve("units"));
    }

    /** Modo ativo do sensor da familia indicada. */
    public String modoDaFamilia(String nome) throws IOException {
        return ler(familia(nome).resolve("mode"));
    }

    /**
     * Troca o modo do sensor da familia indicada.
     */
    public void modoDaFamilia(String nome, String novoModo) throws IOException {
        escrever(familia(nome).resolve("mode"), novoModo);
    }

    public String unidadeDaFamilia(String nome) throws IOException {
        Path u = familia(nome).resolve("units");
        return Files.isReadable(u) ? ler(u) : "";
    }

    /**
     * Leitura ja convertida para a unidade declarada pelo driver.
     *
     */
    public double valor(String endereco) throws IOException {
        return converter(sensor(endereco), 0);
    }

    public int valorCru(String endereco) throws IOException {
        return Integer.parseInt(ler(sensor(endereco).resolve("value0")));
    }

    /** Leitura convertida do sensor da familia indicada, canal {@code indice}. */
    public double valorDaFamilia(String nome, int indice) throws IOException {
        return converter(familia(nome), indice);
    }

    public int valorCruDaFamilia(String nome, int indice) throws IOException {
        return Integer.parseInt(ler(familia(nome).resolve("value" + indice)));
    }

    /** Quantos canais o modo ativo publica. {@code GYRO-G&A} publica dois. */
    public int quantidadeDeValores(String nome) throws IOException {
        Path n = familia(nome).resolve("num_values");
        return Files.isReadable(n) ? Integer.parseInt(ler(n)) : 1;
    }

    private static double converter(Path s, int indice) throws IOException {
        int cru = Integer.parseInt(ler(s.resolve("value" + indice)));
        int casas = 0;
        Path dec = s.resolve("decimals");
        if (Files.isReadable(dec)) {
            casas = Integer.parseInt(ler(dec));
        }
        return casas == 0 ? cru : cru / Math.pow(10, casas);
    }

    // ------------------------------------------------------------------
    // LEDs do bloco
    // ------------------------------------------------------------------

    /**
     * Acende um par de LEDs com as intensidades indicadas.
     * 
     * @param lado      "esquerdo" ou "direito"
     * @param vermelho  fracao de 0.0 a 1.0
     * @param verde     fracao de 0.0 a 1.0
     */
    public void led(String lado, double vermelho, double verde) throws IOException {
        acender(lado + ":vermelho", vermelho);
        acender(lado + ":verde", verde);
    }

    private void acender(String chave, double fracao) throws IOException {
        Path p = leds.get(chave);
        if (p == null) {
            return;
        }
        int v = (int) Math.round(Math.max(0.0, Math.min(1.0, fracao)) * brilhoMaximo);
        escrever(p, Integer.toString(v));
    }

    public void apagarLeds() throws IOException {
        led("esquerdo", 0, 0);
        led("direito", 0, 0);
    }

    // ------------------------------------------------------------------
    // Alto-falante
    // ------------------------------------------------------------------

    private Process beepAtivo;

    private static boolean beepDisponivel() {
        return Files.isExecutable(RAIZ.resolve("usr/bin/beep"))
                || Files.isExecutable(RAIZ.resolve("bin/beep"));
    }

    /**
     * Emite um tom.
     *
     * @param frequencia em hertz; 0 silencia
     * @param duracaoMs  duracao em milissegundos; 0 mantem o tom ate
     *                   
     * @return false se o dispositivo nao tem como gerar som
     */
    public boolean tom(int frequencia, int duracaoMs) throws IOException {
        if (arquivoTom != null) {
            String ordem = duracaoMs > 0
                    ? frequencia + " " + duracaoMs
                    : Integer.toString(frequencia);
            escrever(arquivoTom, ordem);
            return true;
        }
        if (!beepDisponivel()) {
            return false;
        }
        silenciar();
        if (frequencia <= 0) {
            return true;
        }
        // Sem duracao, 'beep' precisa de um valor finito: usa uma hora e conta
        // com silenciar() para interromper. Sem isso o processo terminaria logo
        // e o tom 'continuo' duraria o default do utilitario.
        int ms = duracaoMs > 0 ? duracaoMs : 3600000;
        beepAtivo = new ProcessBuilder("beep", "-f", Integer.toString(frequencia),
                "-l", Integer.toString(ms))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return true;
    }

    public void silenciar() throws IOException {
        if (arquivoTom != null) {
            escrever(arquivoTom, "0");
            return;
        }
        if (beepAtivo != null) {
            beepAtivo.destroy();
            beepAtivo = null;
        }
    }

    // ------------------------------------------------------------------
    // Bateria
    // ------------------------------------------------------------------

    /**
     * Tensao da bateria em volts.
     */
    public double tensaoDaBateria() throws IOException {
        return Integer.parseInt(ler(bateria.resolve("voltage_now"))) / 1_000_000.0;
    }

    /** Corrente consumida em ampere, quando o driver a publica. */
    public double correnteDaBateria() throws IOException {
        Path c = bateria.resolve("current_now");
        if (!Files.isReadable(c)) {
            return Double.NaN;
        }
        return Integer.parseInt(ler(c)) / 1_000_000.0;
    }

    // ------------------------------------------------------------------
    // Acesso a arquivos
    // ------------------------------------------------------------------

    private static String ler(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).trim();
    }

    /**
     * Escreve num atributo do sysfs.
     */
    private static void escrever(Path p, String valor) throws IOException {
        Files.write(p, valor.getBytes(StandardCharsets.UTF_8));
    }
}