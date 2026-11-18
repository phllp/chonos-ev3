package group.chon.javino;

import java.io.IOException;

import group.chon.ev3.hw.Ev3Firmware;

/**
 * Implementação alternativa do Javino para o LEGO Mindstorms EV3.
 *
 * Esta classe ocupa o mesmo pacote e expoe a mesma API pública do Javino original, 
 * de modo que o Argo, que instancia new Javino() e chama os metodos diretamente,
 * funcione sem qualquer alteração.
 */
public class Javino {

    private static final String VERSAO = "1.6.7-ev3";

    /** Mensagem que o Argo envia para solicitar percepcoes. */
    private static final String PEDIDO_DE_PERCEPCOES = "getPercepts";

    private Ev3Firmware firmware;
    private String porta = "";
    private String ultimoDado = "";
    private boolean mostrarStatus = false;
    private int timeout = 1000;

    public Javino() {
    }

    // ------------------------------------------------------------------
    // API pública do Javino original
    // ------------------------------------------------------------------

    public void infoPortStatus(boolean status) {
        this.mostrarStatus = status;
    }

    public void timeout(int novoTimeout) {
        // Mantido por compatibilidade. Sem transferencia serial nao há espera
        // por bytes: uma leitura de sysfs retorna ou lanca exceção.
        this.timeout = novoTimeout;
    }

    public String getPortAddress() {
        return porta;
    }

    public String getJavinoVersion() {
        return VERSAO;
    }

    public void closePort() {
        if (firmware != null) {
            // Parar os motores no fechamento é deliberado: sem isso, encerrar
            // o MAS deixaria o robô seguindo em frente indefinidamente.
            firmware.encerrar();
            firmware = null;
        }
        porta = "";
    }

    /**
     * Envia um comando a camada de firmware.
     *
     * @param PORT porta lógica; qualquer valor é aceito
     * @param MSG  comando textual, ex.: forward(400)
     */
    public boolean sendCommand(String PORT, String MSG) {
        if (!abrir(PORT)) {
            return false;
        }
        boolean ok = firmware.agir(MSG);
        if (mostrarStatus) {
            System.out.println("[javino-ev3] " + MSG + " -> " + ok);
        }
        return ok;
    }

    /**
     * Solicita dados a camada de firmware. O resultado fica disponível em
     * getData().
     */
    public boolean requestData(String PORT, String MSG) {
        if (!abrir(PORT)) {
            return false;
        }

        if (PEDIDO_DE_PERCEPCOES.equalsIgnoreCase(MSG)) {
            ultimoDado = firmware.percepcoes();
        } else {
            // Comandos que não pedem percepções são tratados como ação, e a
            // resposta é o reconhecimento. Preserva o uso de requestData como
            // "envia e le" do protocolo original.
            ultimoDado = firmware.agir(MSG) ? "ok" : "";
        }

        if (mostrarStatus) {
            System.out.println("[javino-ev3] " + MSG + " -> '" + ultimoDado + "'");
        }
        return !ultimoDado.isEmpty();
    }

    public boolean listenArduino(String PORT) {
        if (!abrir(PORT)) {
            return false;
        }
        ultimoDado = firmware.percepcoes();
        return !ultimoDado.isEmpty();
    }

    public String getData() {
        return ultimoDado;
    }

    // ------------------------------------------------------------------

    /**
     * Prepara a camada de firmware.
     *
     * A porta é aceita como rótulo, não como caminho: no EV3 não há 
     * dispositivo serial a abrir. O valor informado fica registrado para
     * diagnóstico. 
     */
    private boolean abrir(String PORT) {
        if (firmware != null) {
            return true;
        }
        try {
            firmware = new Ev3Firmware();
            porta = (PORT == null) ? "" : PORT;
            if (mostrarStatus) {
                System.out.println("[javino-ev3] firmware sysfs pronto (porta '"
                        + porta + "', versao " + VERSAO + ")");
            }
            return true;
        } catch (IOException e) {
            System.err.println("[javino-ev3] nao foi possivel acessar o sysfs: "
                    + e.getMessage());
            return false;
        }
    }
}
