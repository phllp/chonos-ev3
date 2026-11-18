package group.chon.ev3.hw;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Firmware virtual do EV3.
 */
public final class Ev3Firmware {

    // ------------------------------------------------------------------
    // Montagem
    //
    // Convencao de portas: Motores são atribuídos por endereço porque a função de cada um é 
    // uma decisão mecânica: qual e o esquerdo depende de como o chassi foi montado, não do
    // modelo do motor. Sensores NAO tem convenção de porta - são descobertos
    // pelo driver, em qualquer entrada.
    // ------------------------------------------------------------------

    private static final String MOTOR_ESQ =
            System.getProperty("ev3.motor.esquerdo", "outA");
    private static final String MOTOR_DIR =
            System.getProperty("ev3.motor.direito", "outB");
    private static final String MOTOR_FERRAMENTA =
            System.getProperty("ev3.motor.ferramenta", "outC");

    private static final int VELOCIDADE_PADRAO =
            Integer.getInteger("ev3.velocidade", 400);

    /** Velocidade usada nos giros, tipicamente menor que a de avanco. */
    private static final int VELOCIDADE_GIRO =
            Integer.getInteger("ev3.velocidade.giro", 250);

    /** O motor medio aceita ate 1560 pulsos/s, mas tem menos torque. */
    private static final int VELOCIDADE_FERRAMENTA =
            Integer.getInteger("ev3.velocidade.ferramenta", 500);

    private static final int FREQUENCIA_PADRAO =
            Integer.getInteger("ev3.buzzer.frequencia", 1000);

    private static final int DURACAO_PADRAO =
            Integer.getInteger("ev3.buzzer.duracao", 200);

    /**
     * Intervalo minimo entre leituras da bateria, em milissegundos.
     */
    private static final long INTERVALO_BATERIA =
            Long.getLong("ev3.bateria.intervalo", 5000L);

    private static final Pattern COMANDO =
            Pattern.compile("\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:\\(([^)]*)\\))?\\s*");

    /** Nomes dos valores do sensor de cor em modo COL-COLOR, na ordem do driver. */
    private static final String[] CORES = {
        "none", "black", "blue", "green", "yellow", "red", "white", "brown"
    };

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private final Ev3Hardware hw;

    private final boolean temChassi;
    private final boolean temFerramenta;
    private final boolean temUltrassonico;
    private final boolean temGiroscopio;
    private final boolean temToque;
    private final boolean temCor;
    private final boolean temLeds;
    private final boolean temAltoFalante;
    private final boolean temBateria;

    /** Trecho constante das percepcoes: has(chassis);has(ultrasonic);... */
    private final String prefixoCapacidades;

    /** Grupos de percepcao habilitados; vazio significa todos. */
    private final Set<String> gruposAtivos;

    /** Modo ativo lido de volta do driver, nao o que tentamos escrever. */
    private String modoGiro = "";
    private String modoCor = "";

    /** Estado declarado dos motores do chassi, no vocabulario dos ChonBots. */
    private volatile String estadoMotor = "stopped";

    private double tensaoCache = Double.NaN;
    private long tensaoLidaEm = 0L;

    private final Set<String> jaAvisados = new HashSet<>();

    public Ev3Firmware() throws IOException {
        this.hw = new Ev3Hardware();

        this.temChassi = hw.temMotor(MOTOR_ESQ) && hw.temMotor(MOTOR_DIR);
        this.temFerramenta = hw.temMotor(MOTOR_FERRAMENTA);
        this.temUltrassonico = hw.temFamilia(Ev3Hardware.ULTRASSONICO);
        this.temGiroscopio = hw.temFamilia(Ev3Hardware.GIROSCOPIO);
        this.temToque = hw.temFamilia(Ev3Hardware.TOQUE);
        this.temCor = hw.temFamilia(Ev3Hardware.COR);
        this.temLeds = hw.temLeds();
        this.temAltoFalante = hw.temAltoFalante();
        this.temBateria = hw.temBateria();

        this.gruposAtivos = lerGrupos();

        imprimeInventario();
        prepararChassi();
        prepararFerramenta();
        prepararSensores();

        this.prefixoCapacidades = montarCapacidades();

        Runtime.getRuntime().addShutdownHook(new Thread(this::encerrar,
                "ev3-parada-de-emergencia"));
    }

    private static Set<String> lerGrupos() {
        String p = System.getProperty("ev3.percepts", "").trim();
        if (p.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> s = new LinkedHashSet<>();
        for (String g : p.split("\\s*,\\s*")) {
            if (!g.isEmpty()) {
                s.add(g.toLowerCase(Locale.ROOT));
            }
        }
        return s;
    }

    private boolean grupo(String nome) {
        return gruposAtivos.isEmpty() || gruposAtivos.contains(nome);
    }

    /**
     * Registra no log o que foi encontrado e o que falta.
     */
    private void imprimeInventario() {
        System.err.println("[ev3] chassi      : "
                + (temChassi ? MOTOR_ESQ + "/" + MOTOR_DIR : "AUSENTE"));
        System.err.println("[ev3] ferramenta  : "
                + (temFerramenta ? MOTOR_FERRAMENTA : "ausente"));
        System.err.println("[ev3] sensores    : " + hw.familiasDeSensores()
                + " em " + hw.enderecosDeSensores());
        System.err.println("[ev3] leds        : " + (temLeds ? "ok" : "ausente"));
        System.err.println("[ev3] alto-falante: " + (temAltoFalante ? "ok" : "ausente"));
        System.err.println("[ev3] bateria     : " + (temBateria ? "ok" : "ausente"));

        if (!temChassi) {
            System.err.println("[ev3] AVISO: chassi incompleto. Motores presentes: "
                    + hw.enderecosDeMotores()
                    + ". Ajuste com -Dev3.motor.esquerdo=outX -Dev3.motor.direito=outY");
        }
        if (!temUltrassonico) {
            System.err.println("[ev3] AVISO: nenhum ultrassonico conectado; a "
                    + "percepcao distance/1 nao sera emitida e planos de desvio "
                    + "de obstaculo nao vao disparar");
        }
        if (!gruposAtivos.isEmpty()) {
            System.err.println("[ev3] percepcoes restritas a: " + gruposAtivos);
        }
    }

    private void prepararChassi() throws IOException {
        if (!temChassi) {
            return;
        }
        // O padrao de fabrica e 'coast', que faz o robo deslizar ao parar.
        // Inadequado para um agente que para diante de obstaculo.
        hw.acaoDeParada(MOTOR_ESQ, "brake");
        hw.acaoDeParada(MOTOR_DIR, "brake");

        // Um motor medio no chassi anda, mas com torque e relacao de velocidade
        // diferentes do grande, e o robo sai de curva sem razao aparente.
        try {
            if (!hw.motorEhGrande(MOTOR_ESQ) || !hw.motorEhGrande(MOTOR_DIR)) {
                System.err.println("[ev3] AVISO: o chassi esperava dois motores "
                        + "grandes; encontrados " + hw.driverDeMotor(MOTOR_ESQ)
                        + " e " + hw.driverDeMotor(MOTOR_DIR));
            }
        } catch (IOException e) {
            // driver_name indisponivel: segue sem a validacao
        }
    }

    private void prepararFerramenta() throws IOException {
        if (!temFerramenta) {
            return;
        }
        // 'hold' em vez de 'brake': uma garra fechada ou um braco levantado
        // precisa resistir ao peso depois de parar, nao apenas deixar de
        // empurrar.
        hw.acaoDeParada(MOTOR_FERRAMENTA, "hold");
    }

    /**
     * Fixa o modo de cada sensor e le de volta o que o driver aceitou.
     */
    private void prepararSensores() throws IOException {
        if (temUltrassonico) {
            tentarModo(Ev3Hardware.ULTRASSONICO, "US-DIST-CM");
        }
        if (temGiroscopio) {
            // GYRO-G&A publica angulo e taxa nos dois canais de uma vez, o que
            // evita trocar de modo para obter as duas grandezas.
            tentarModo(Ev3Hardware.GIROSCOPIO, "GYRO-G&A");
            modoGiro = hw.modoDaFamilia(Ev3Hardware.GIROSCOPIO);
        }
        if (temToque) {
            tentarModo(Ev3Hardware.TOQUE, "TOUCH");
        }
        if (temCor) {
            tentarModo(Ev3Hardware.COR, "COL-COLOR");
            modoCor = hw.modoDaFamilia(Ev3Hardware.COR);
        }
    }

    private void tentarModo(String familia, String modo) {
        try {
            hw.modoDaFamilia(familia, modo);
        } catch (IOException | IllegalArgumentException e) {
            try {
                System.err.println("[ev3] AVISO: modo '" + modo + "' recusado pelo "
                        + "sensor " + familia + "; modo ativo: "
                        + hw.modoDaFamilia(familia) + "; disponiveis neste "
                        + "dispositivo podem ser consultados no diagnostico");
            } catch (IOException ignored) {
                // nada mais a relatar
            }
        }
    }

    /**
     * Monta o trecho "has/1" das percepcoes, uma unica vez.
     * Essas percepções respondem a pergunta "o que este dispositivo sabe fazer". 
     */
    private String montarCapacidades() {
        StringBuilder sb = new StringBuilder();
        if (temChassi) {
            sb.append("has(chassis)");
        }
        if (temFerramenta) {
            anexar(sb, "has(tool)");
        }
        if (temUltrassonico) {
            anexar(sb, "has(ultrasonic)");
        }
        if (temGiroscopio) {
            anexar(sb, "has(gyro)");
        }
        if (temToque) {
            anexar(sb, "has(touch)");
        }
        if (temCor) {
            anexar(sb, "has(color)");
        }
        if (temLeds) {
            anexar(sb, "has(led)");
        }
        if (temAltoFalante) {
            anexar(sb, "has(buzzer)");
        }
        if (temBateria) {
            anexar(sb, "has(battery)");
        }
        return sb.toString();
    }

    private static void anexar(StringBuilder sb, String trecho) {
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(trecho);
    }

    // ==================================================================
    // Percepções
    // ==================================================================

    /**
     * Monta a string de percepções que o Argo vai dividir por ponto e virgula.
     */
    public String percepcoes() {
        StringBuilder sb = new StringBuilder();

        if (grupo("capabilities") && !prefixoCapacidades.isEmpty()) {
            sb.append(prefixoCapacidades);
        }

        if (temUltrassonico && grupo("distance")) {
            try {
                anexar(sb, String.format(Locale.US, "distance(%.1f)",
                        hw.valorDaFamilia(Ev3Hardware.ULTRASSONICO, 0)));
            } catch (IOException e) {
                // Uma leitura falha nao deve derrubar o ciclo de raciocinio.
                anexar(sb, "sensorError(ultrasonic)");
            }
        } else if (!temUltrassonico && grupo("distance")) {
            // Percepcao legada de ausencia, mantida para nao quebrar planos
            // escritos antes de has/1 existir. Prefira 'not has(ultrasonic)'.
            anexar(sb, "noSensor");
        }

        if (temGiroscopio && grupo("gyro")) {
            percepcoesDoGiroscopio(sb);
        }

        if (temToque && grupo("touch")) {
            try {
                anexar(sb, "touch("
                        + (hw.valorCruDaFamilia(Ev3Hardware.TOQUE, 0) != 0
                           ? "pressed" : "released") + ")");
            } catch (IOException e) {
                anexar(sb, "sensorError(touch)");
            }
        }

        if (temCor && grupo("color")) {
            percepcoesDoSensorDeCor(sb);
        }

        if (temChassi && grupo("pos")) {
            try {
                anexar(sb, String.format(Locale.US, "pos(%d,%d)",
                        hw.posicao(MOTOR_ESQ), hw.posicao(MOTOR_DIR)));
            } catch (IOException e) {
                // posicao indisponivel: omite em vez de inventar valor
            }
        }

        if (temChassi && grupo("motor")) {
            // Percepcao motor(M) no vocabulário dos ChonBots: running,
            // stopped, backward, turningLeft, turningRight. Permite que um
            // plano escrito para o ChonBot - como '+!explorar : not
            // motor(running)' - funcione sem alteração neste dispositivo.
            anexar(sb, "motor(" + estadoMotor + ')');

            // Diferente de motor/1, esta vem do driver e nao da intencao do
            // software: e o unico caminho para o agente perceber que mandou
            // andar e a roda nao girou.
            try {
                if (hw.estado(MOTOR_ESQ).contains("stalled")
                        || hw.estado(MOTOR_DIR).contains("stalled")) {
                    anexar(sb, "stalled");
                }
            } catch (IOException e) {
                // estado indisponivel: omite
            }
        }

        if (temFerramenta && grupo("tool")) {
            try {
                anexar(sb, String.format(Locale.US, "toolPos(%d)",
                        hw.posicao(MOTOR_FERRAMENTA)));
            } catch (IOException e) {
                // omite
            }
        }

        if (temBateria && grupo("battery")) {
            double v = tensao();
            if (!Double.isNaN(v)) {
                anexar(sb, String.format(Locale.US, "battery(%.2f)", v));
            }
        }

        return sb.toString();
    }

    private void percepcoesDoGiroscopio(StringBuilder sb) {
        try {
            if (modoGiro.contains("G&A")) {
                anexar(sb, String.format(Locale.US, "angle(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.GIROSCOPIO, 0)));
                anexar(sb, String.format(Locale.US, "rate(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.GIROSCOPIO, 1)));
            } else if (modoGiro.contains("RATE")) {
                anexar(sb, String.format(Locale.US, "rate(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.GIROSCOPIO, 0)));
            } else {
                // GYRO-ANG, TILT-ANG e qualquer modo de angulo
                anexar(sb, String.format(Locale.US, "angle(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.GIROSCOPIO, 0)));
            }
        } catch (IOException e) {
            anexar(sb, "sensorError(gyro)");
        }
    }

    private void percepcoesDoSensorDeCor(StringBuilder sb) {
        try {
            if (modoCor.contains("COL-COLOR")) {
                int i = hw.valorCruDaFamilia(Ev3Hardware.COR, 0);
                anexar(sb, "color(" + (i >= 0 && i < CORES.length
                        ? CORES[i] : "none") + ')');
            } else if (modoCor.contains("REFLECT")) {
                anexar(sb, String.format(Locale.US, "reflect(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 0)));
            } else if (modoCor.contains("AMBIENT")) {
                anexar(sb, String.format(Locale.US, "ambient(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 0)));
            } else if (modoCor.contains("RGB")) {
                anexar(sb, String.format(Locale.US, "rgb(%d,%d,%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 0),
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 1),
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 2)));
            } else {
                anexar(sb, String.format(Locale.US, "colorRaw(%d)",
                        hw.valorCruDaFamilia(Ev3Hardware.COR, 0)));
            }
        } catch (IOException e) {
            anexar(sb, "sensorError(color)");
        }
    }

    /** Tensão da bateria com cache, para não ler um arquivo a cada ciclo. */
    private double tensao() {
        long agora = System.currentTimeMillis();
        if (Double.isNaN(tensaoCache) || agora - tensaoLidaEm >= INTERVALO_BATERIA) {
            try {
                tensaoCache = hw.tensaoDaBateria();
                tensaoLidaEm = agora;
            } catch (IOException e) {
                return Double.NaN;
            }
        }
        return tensaoCache;
    }

    // ==================================================================
    // Ações
    // ==================================================================

    /**
     * Executa um comando textual.
     * Aceita tanto "forward(300)" quanto forward(300). O
     *
     * @return true se o comando foi reconhecido e executado
     */
    public boolean agir(String comando) {
        if (comando == null) {
            return false;
        }

        String cmd = comando.trim();
        if (cmd.length() >= 2 && cmd.startsWith("\"") && cmd.endsWith("\"")) {
            cmd = cmd.substring(1, cmd.length() - 1).trim();
        }
        if (cmd.isEmpty()) {
            return false;
        }

        Matcher m = COMANDO.matcher(cmd);
        if (!m.matches()) {
            return false;
        }

        String nome = m.group(1).toLowerCase(Locale.ROOT);
        String args = (m.group(2) == null) ? "" : m.group(2).trim();

        try {
            switch (nome) {

                // =========================================================
                // CHASSI - vocabulario dos ChonBots (ChonBot-2WD e similares)
                // =========================================================

                case "goahead":
                    return mover(VELOCIDADE_PADRAO, VELOCIDADE_PADRAO);

                case "goback":
                    return mover(-VELOCIDADE_PADRAO, -VELOCIDADE_PADRAO);

                case "goleft":
                    return mover(-VELOCIDADE_GIRO, VELOCIDADE_GIRO);

                case "goright":
                    return mover(VELOCIDADE_GIRO, -VELOCIDADE_GIRO);

                // =========================================================
                // CHASSI - vocabulário próprio, com velocidade parametrizavel
                // =========================================================

                case "stop":
                    return parar();

                case "reset":
                    if (!temChassi) {
                        return avisarUmaVez(nome, "sem chassi");
                    }
                    hw.zerarPosicao(MOTOR_ESQ);
                    hw.zerarPosicao(MOTOR_DIR);
                    return true;

                case "forward":
                    return mover(velocidade(args), velocidade(args));

                case "backward":
                    return mover(-velocidade(args), -velocidade(args));

                case "left":
                    return mover(-velocidade(args), velocidade(args));

                case "right":
                    return mover(velocidade(args), -velocidade(args));

                case "move": {
                    int[] p = inteiros(args, 2);
                    return p != null && mover(p[0], p[1]);
                }

                // Modo de parada do chassi. 'brake' é o padrão definido na
                // inicialização; 'coast' solta as rodas e 'hold' resiste a
                // empurrões. Afeta todo 'stop' subsequente.
                case "brake":
                case "coast":
                case "hold":
                    if (!temChassi) {
                        return avisarUmaVez(nome, "sem chassi");
                    }
                    hw.acaoDeParada(MOTOR_ESQ, nome);
                    hw.acaoDeParada(MOTOR_DIR, nome);
                    return true;

                // =========================================================
                // FERRAMENTA - motor médio
                // =========================================================

                case "tool": {
                    if (!temFerramenta) {
                        return avisarUmaVez(nome, "sem motor medio em "
                                + MOTOR_FERRAMENTA);
                    }
                    hw.girar(MOTOR_FERRAMENTA, velocidadeFerramenta(args));
                    return true;
                }

                case "toolstop":
                    if (!temFerramenta) {
                        return avisarUmaVez(nome, "sem motor medio");
                    }
                    hw.parar(MOTOR_FERRAMENTA);
                    return true;

                // Deslocamento controlado pelo driver: a ação retorna na hora e
                // o agente continua deliberando enquanto o motor se move. E a
                // alternativa a encadear tool(V) com .wait, que bloqueia o
                // ciclo de raciocínio pela duracao da manobra.
                case "toolby": {
                    if (!temFerramenta) {
                        return avisarUmaVez(nome, "sem motor medio");
                    }
                    int[] p = inteirosFlexiveis(args, 1, 2);
                    if (p == null) {
                        return false;
                    }
                    hw.girarPor(MOTOR_FERRAMENTA, p[0],
                            p.length > 1 ? p[1] : VELOCIDADE_FERRAMENTA);
                    return true;
                }

                case "toolto": {
                    if (!temFerramenta) {
                        return avisarUmaVez(nome, "sem motor medio");
                    }
                    int[] p = inteirosFlexiveis(args, 1, 2);
                    if (p == null) {
                        return false;
                    }
                    hw.girarAte(MOTOR_FERRAMENTA, p[0],
                            p.length > 1 ? p[1] : VELOCIDADE_FERRAMENTA);
                    return true;
                }

                case "toolreset":
                    if (!temFerramenta) {
                        return avisarUmaVez(nome, "sem motor medio");
                    }
                    hw.zerarPosicao(MOTOR_FERRAMENTA);
                    return true;

                // =========================================================
                // ALTO-FALANTE
                // =========================================================

                case "beep":
                    return som(FREQUENCIA_PADRAO, DURACAO_PADRAO, nome);

                case "tone": {
                    int[] p = inteirosFlexiveis(args, 1, 2);
                    if (p == null) {
                        return false;
                    }
                    return som(p[0], p.length > 1 ? p[1] : 0, nome);
                }

                case "buzzeron":
                    return som(FREQUENCIA_PADRAO, 0, nome);

                case "buzzeroff":
                    if (!temAltoFalante) {
                        return avisarUmaVez(nome, "sem alto-falante");
                    }
                    hw.silenciar();
                    return true;

                // =========================================================
                // LEDS DO BLOCO
                //
                // Cada lado tem um LED vermelho e um verde no mesmo ponto; as
                // demais cores sao proporcoes dos dois. Nao existe LED ambar
                // no hardware.
                // =========================================================

                case "led":
                    return pintar("ambos", args, nome);

                case "ledleft":
                    return pintar("esquerdo", args, nome);

                case "ledright":
                    return pintar("direito", args, nome);

                case "flashlon":
                    return pintar("esquerdo", "amber", nome);

                case "flashron":
                    return pintar("direito", "amber", nome);

                case "flashlightoff":
                    return pintar("ambos", "off", nome);

                case "breaklon":
                    return pintar("ambos", "red", nome);

                case "breakloff":
                    return pintar("ambos", "off", nome);

                // =========================================================
                // MODO DOS SENSORES
                //
                // Um sensor LEGO mede uma grandeza por vez. Estas ações
                // escolhem qual, e a percepção emitida muda junto.
                // =========================================================

                case "colormode":
                    return modoDeSensor(Ev3Hardware.COR, args, nome);

                case "gyromode":
                    return modoDeSensor(Ev3Hardware.GIROSCOPIO, args, nome);

                // Reentra no modo ativo, o que zera o angulo acumulado. E o
                // equivalente a 'agora estou apontado para zero graus'.
                case "gyroreset": {
                    if (!temGiroscopio) {
                        return avisarUmaVez(nome, "sem giroscopio");
                    }
                    String anterior = modoGiro.isEmpty() ? "GYRO-G&A" : modoGiro;
                    hw.modoDaFamilia(Ev3Hardware.GIROSCOPIO, "GYRO-RATE");
                    hw.modoDaFamilia(Ev3Hardware.GIROSCOPIO, anterior);
                    modoGiro = hw.modoDaFamilia(Ev3Hardware.GIROSCOPIO);
                    return true;
                }

                default:
                    return false;
            }
        // IllegalArgumentException cobre NumberFormatException, que e subclasse
        // dela, e tambem o 'dispositivo nao encontrado' do Ev3Hardware.
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Auxiliares das ações
    // ------------------------------------------------------------------

    private boolean som(int frequencia, int duracao, String nome) throws IOException {
        if (!temAltoFalante) {
            return avisarUmaVez(nome, "sem alto-falante e sem utilitario beep");
        }
        return hw.tom(frequencia, duracao);
    }

    private boolean pintar(String lado, String cor, String nome) throws IOException {
        if (!temLeds) {
            return avisarUmaVez(nome, "sem LEDs controlaveis");
        }
        String c = cor.trim().toLowerCase(Locale.ROOT);
        double vermelho;
        double verde;
        switch (c) {
            case "":
            case "off":
                vermelho = 0.0; verde = 0.0; break;
            case "red":
                vermelho = 1.0; verde = 0.0; break;
            case "green":
                vermelho = 0.0; verde = 1.0; break;
            case "amber":
                vermelho = 1.0; verde = 1.0; break;
            case "orange":
                vermelho = 1.0; verde = 0.4; break;
            case "yellow":
                vermelho = 0.6; verde = 1.0; break;
            default:
                return false;
        }
        if ("ambos".equals(lado)) {
            hw.led("esquerdo", vermelho, verde);
            hw.led("direito", vermelho, verde);
        } else {
            hw.led(lado, vermelho, verde);
        }
        return true;
    }

    private boolean modoDeSensor(String familia, String args, String nome)
            throws IOException {
        if (!hw.temFamilia(familia)) {
            return avisarUmaVez(nome, "sensor " + familia + " nao conectado");
        }
        String a = args.trim().toLowerCase(Locale.ROOT);
        String modo;
        if (Ev3Hardware.COR.equals(familia)) {
            switch (a) {
                case "color":   modo = "COL-COLOR";   break;
                case "reflect": modo = "COL-REFLECT"; break;
                case "ambient": modo = "COL-AMBIENT"; break;
                case "rgb":     modo = "RGB-RAW";     break;
                default: return false;
            }
        } else {
            switch (a) {
                case "angle": modo = "GYRO-ANG";  break;
                case "rate":  modo = "GYRO-RATE"; break;
                case "both":  modo = "GYRO-G&A";  break;
                default: return false;
            }
        }
        hw.modoDaFamilia(familia, modo);
        // Le de volta: o formato da percepcao segue o que o driver aceitou,
        // nao o que pedimos.
        if (Ev3Hardware.COR.equals(familia)) {
            modoCor = hw.modoDaFamilia(familia);
        } else {
            modoGiro = hw.modoDaFamilia(familia);
        }
        return true;
    }

    /**
     * Registra, uma única vez por ação, que o dispositivo exigido não existe
     * nesta montagem.
     */
    private boolean avisarUmaVez(String acao, String motivo) {
        if (jaAvisados.add(acao)) {
            System.err.println("[ev3] acao '" + acao + "' nao se aplica a esta "
                    + "montagem (" + motivo + "); aceita como no-op para nao "
                    + "abortar o plano. Use 'has/1' na guarda do plano para "
                    + "decidir antes de agir.");
        }
        return true;
    }

    private int velocidade(String args) {
        return comPadrao(args, VELOCIDADE_PADRAO);
    }

    private int velocidadeFerramenta(String args) {
        return comPadrao(args, VELOCIDADE_FERRAMENTA);
    }

    private static int comPadrao(String args, int padrao) {
        if (args.isEmpty()) {
            return padrao;
        }
        try {
            return numero(args);
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    /**
     * Converte um argumento numerico, tolerando a forma decimal.
     *
     * @throws NumberFormatException se nao for numero, ou se for infinito ou
     *         NaN - casos em que o arredondamento produziria um valor
     *         silenciosamente errado em vez de uma recusa
     */
    private static int numero(String s) {
        double d = Double.parseDouble(s.trim());
        if (!Double.isFinite(d)) {
            throw new NumberFormatException("valor nao finito: " + s);
        }
        return (int) Math.round(d);
    }

    /** Exatamente {@code esperado} inteiros separados por virgula, ou null. */
    private static int[] inteiros(String args, int esperado) {
        int[] v = inteirosFlexiveis(args, esperado, esperado);
        return v;
    }

    /** Entre {@code min} e {@code max} inteiros separados por virgula, ou null. */
    private static int[] inteirosFlexiveis(String args, int min, int max) {
        if (args.isEmpty()) {
            return min == 0 ? new int[0] : null;
        }
        String[] partes = args.split("\\s*,\\s*");
        if (partes.length < min || partes.length > max) {
            return null;
        }
        int[] v = new int[partes.length];
        for (int i = 0; i < partes.length; i++) {
            try {
                v[i] = numero(partes[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return v;
    }

    private boolean mover(int esquerdo, int direito) throws IOException {
        if (!temChassi) {
            return false;
        }
        hw.girar(MOTOR_ESQ, esquerdo);
        hw.girar(MOTOR_DIR, direito);

        // Mantem o estado declarado na percepcao motor(M) coerente com o que
        // foi comandado. Nao e leitura do hardware: e a intencao vigente, que
        // e o que o vocabulario dos ChonBots expressa.
        if (esquerdo > 0 && direito > 0) {
            estadoMotor = "running";
        } else if (esquerdo < 0 && direito < 0) {
            estadoMotor = "backward";
        } else if (esquerdo < 0) {
            estadoMotor = "turningLeft";
        } else if (direito < 0) {
            estadoMotor = "turningRight";
        } else {
            estadoMotor = "stopped";
        }
        return true;
    }

    private boolean parar() throws IOException {
        if (!temChassi) {
            return false;
        }
        hw.parar(MOTOR_ESQ);
        hw.parar(MOTOR_DIR);
        estadoMotor = "stopped";
        return true;
    }

    /**
     * Desliga tudo ao encerrar.
     */
    public void encerrar() {
        try {
            parar();
        } catch (IOException e) {
            // nada a fazer no encerramento
        }
        if (temFerramenta) {
            try {
                hw.acaoDeParada(MOTOR_FERRAMENTA, "coast");
                hw.parar(MOTOR_FERRAMENTA);
            } catch (IOException e) {
                // segue
            }
        }
        if (temAltoFalante) {
            try {
                hw.silenciar();
            } catch (IOException e) {
                // segue
            }
        }
        if (temLeds) {
            try {
                hw.apagarLeds();
            } catch (IOException e) {
                // segue
            }
        }
    }
}