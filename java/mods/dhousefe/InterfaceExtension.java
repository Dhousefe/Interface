package mods.dhousefe;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;

import ext.mods.commons.data.StatSet;
import ext.mods.commons.data.xml.IXmlReader;
import ext.mods.commons.logging.CLogger;
import ext.mods.extensions.interfaces.L2JExtension;
import ext.mods.extensions.listener.command.OnBypassCommandListener;
import ext.mods.extensions.listener.manager.BypassCommandManager;
import ext.mods.gameserver.communitybbs.CustomCommunityBoard;
import ext.mods.gameserver.data.SkillTable;
import ext.mods.gameserver.enums.GaugeColor;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.handler.IItemHandler;
import ext.mods.gameserver.handler.IVoicedCommandHandler;
import ext.mods.gameserver.handler.ItemHandler;
import ext.mods.gameserver.handler.VoicedCommandHandler;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager.AutoFarmType;
import ext.mods.gameserver.model.entity.autofarm.ZoneBuilder;
import ext.mods.gameserver.model.item.instance.ItemInstance;
import ext.mods.gameserver.model.location.Location;
import ext.mods.gameserver.network.SystemMessageId;
import ext.mods.gameserver.network.serverpackets.ActionFailed;
import ext.mods.gameserver.network.serverpackets.CreatureSay;
import ext.mods.gameserver.network.serverpackets.ExAutoSoulShot;
import ext.mods.gameserver.network.serverpackets.ExShowVariationMakeWindow;
import ext.mods.gameserver.network.serverpackets.MagicSkillUse;
import ext.mods.gameserver.network.serverpackets.NpcHtmlMessage;
import ext.mods.gameserver.network.serverpackets.SetupGauge;
import ext.mods.gameserver.network.serverpackets.SystemMessage;

/**
 * Extensao modular de interface para Lineage 2 Interlude (L2JExtension).
 * Opera de forma desacoplada do nucleo com suporte a Java 21 e Virtual Threads.
 */
public final class InterfaceExtension implements L2JExtension, OnBypassCommandListener, IVoicedCommandHandler {

    private static final CLogger LOGGER = new CLogger(InterfaceExtension.class.getName());
    private static final String BYPASS_PREFIX = "voiced_interface";

    private int _teleportCastTime = 15000;
    private int _teleportSkillAnimationId = 2039;
    private int[] _allowedMultisells = {};

    private volatile String _cachedHtmlTemplate = null;
    private volatile long _lastHtmlMtime = 0L;

    private final Map<Integer, Long> _teleportCooldowns = new ConcurrentHashMap<>();
    private final ExecutorService _virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Inicializa arquivos padrao, carrega configuracoes, dados de teleporte e registra handlers.
     */
    @Override
    public void onLoad() {
        try {
            copyResourceIfNotExists("mods/htmls/index.html", "./data/locale/en_US/html/interface/index.html");
            copyResourceIfNotExists("mods/configs/InterfaceConfig.ini", "./config/InterfaceConfig.ini");
            copyResourceIfNotExists("mods/xmls/teleportLocationsInterface.xml", "./data/custom/mods/teleportLocationsInterface.xml");
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao criar arquivos de configuracao padrao.", e);
            return;
        }

        loadConfigsFromIni();
        TeleportLocationData.getInstance().load();
        BypassCommandManager.getInstance().registerBypassListener(this);
        VoicedCommandHandler.getInstance().registerHandler(this);

        LOGGER.info("[" + getName() + "] Carregado e registrado com sucesso.");
    }

    /**
     * Desregistra os listeners e encerra o executor de threads virtuais com seguranca.
     */
    @Override
    public void onDisable() {
        BypassCommandManager.getInstance().unregisterBypassListener(this);
        VoicedCommandHandler.getInstance().unregisterHandler(this);
        _virtualThreadExecutor.shutdown();
        try {
            if (!_virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                _virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            _virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[" + getName() + "] Descarregado.");
    }

    /**
     * Identificador textual unico da extensao.
     */
    @Override
    public String getName() {
        return "Interface_BrProject";
    }

    /**
     * Intercepta bypasses do cliente e roteia para execucao sincrona ou assincrona em O(1).
     */
    @Override
    public boolean onBypass(Player player, String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        char first = command.charAt(0);
        boolean matches = switch (first) {
            case 'v' -> command.startsWith(BYPASS_PREFIX);
            case 'R' -> command.startsWith("RequestAutoShot:");
            case 'G' -> command.startsWith("GkGo ");
            case 'B' -> command.startsWith("BuffEngine_Dispel");
            case 'a' -> command.startsWith("autofarm");
            case '_' -> command.startsWith("_autofarm") || 
                        command.equals("_infosettings") || 
                        command.startsWith("_radiusAutoFarm") || 
                        command.equals("_daniloAugment");
            case 'r' -> command.equals("raid");
            case 'b' -> command.equals("bstatus") || command.equals("bp_openhtml mods/lucky/40079.htm");
            case 'e' -> command.equals("email") || command.equals("epic");
            case 'p' -> command.equals("premium");
            case 's' -> command.equals("skin");
            case 't' -> command.equals("topenchant") || command.equals("tour");
            default -> false;
        };

        if (matches) {
            if (command.equals("voiced_interface Interfaceemail") || command.equals("email")) {
                handleBypass(player, "Interfaceemail");
            } else if (command.equals("_daniloAugment")) {
                handleAugmentOpen(player);
            } else {
                _virtualThreadExecutor.execute(() -> handleCommandAsync(player, command));
            }
            return true;
        }

        return false;
    }

    /**
     * Processa comandos em threads virtuais com despacho rapido baseado no primeiro caractere.
     */
    private void handleCommandAsync(Player player, String command) {
        try {
            if (command == null || command.isEmpty() || command.equals("email") || command.equals("voiced_interface Interfaceemail")) {
                return;
            }

            char first = command.charAt(0);
            switch (first) {
                case 'v' -> {
                    if (command.startsWith(BYPASS_PREFIX)) {
                        final String actualCommand = command.substring(BYPASS_PREFIX.length()).trim();
                        handleBypass(player, actualCommand);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'R' -> {
                    if (command.startsWith("RequestAutoShot:")) {
                        handleRequestAutoShot(player, command);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'G' -> {
                    if (command.startsWith("GkGo ")) {
                        handleTeleportRequest(player, command);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'B' -> {
                    if (command.startsWith("BuffEngine_Dispel")) {
                        handleBypass(player, command);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'a' -> {
                    if (command.startsWith("autofarm")) {
                        handleAutoFarm(player, command);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case '_' -> {
                    if (command.startsWith("_autofarm")) {
                        handleAutoFarm(player, command);
                    } else if (command.equals("_infosettings")) {
                        AutoFarmManager.getInstance().handleBypass(player, "skills page 1");
                    } else if (command.startsWith("_radiusAutoFarm")) {
                        handleRadiusAutoFarm(player, command);
                    } else if (command.equals("_daniloAugment")) {
                        handleAugmentOpen(player);
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'p' -> {
                    if (command.equals("premium")) {
                        handleBypass(player, "PremiumStatus");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'e' -> {
                    if (command.equals("epic")) {
                        handleBypass(player, "EpicStatus");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 's' -> {
                    if (command.equals("skin")) {
                        handleBypass(player, "SkinStatus");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 't' -> {
                    if (command.equals("topenchant")) {
                        handleBypass(player, "TopEnchant");
                    } else if (command.equals("tour")) {
                        handleBypass(player, "TourStatus");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'd' -> {
                    if (command.startsWith("donate")) {
                        handleBypass(player, "_bbsgetfav_add");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                case 'b' -> {
                    if (command.startsWith("bstatus")) {
                        handleBypass(player, "statistic");
                    } else if (command.equals("bp_openhtml mods/lucky/40079.htm")) {
                        handleBypass(player, ".raid");
                    } else {
                        handleUnknownCommand(player, command);
                    }
                }
                default -> handleUnknownCommand(player, command);
            }
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao processar comando '" + command + "' para " + player.getName(), e);
        }
    }

    /**
     * Registra alerta para comandos nao reconhecidos e envia ActionFailed para liberar o cliente.
     */
    private void handleUnknownCommand(Player player, String command) {
        LOGGER.warn("[" + getName() + "] Comando de bypass desconhecido '" + command + "' do jogador " + player.getName());
        player.sendPacket(ActionFailed.STATIC_PACKET);
    }

    /**
     * Interpreta acoes estruturadas (loja, teleporte, status de bosses e integracoes).
     */
    public void handleBypass(Player player, String bypass) {
        var parts = bypass.split(" ", 2);
        var action = parts[0];
        var arguments = parts.length > 1 ? parts[1] : "";

        if (action.startsWith("BuffEngine_Dispel")) {
            var p = action.split("=");
            if (p.length == 2) {
                try {
                    int skillId = Integer.parseInt(p[1]);
                    player.stopSkillEffects(skillId);
                } catch (NumberFormatException e) {
                    LOGGER.warn("[" + getName() + "] Bypass BuffEngine_Dispel com skillId invalido: " + p[1]);
                }
            }
            return;
        }

        switch (action) {
            case "GkGo" -> {
                if (!arguments.isEmpty()) handleTeleportRequest(player, arguments);
            }
            case "Shop" -> {
                if (!arguments.isEmpty()) openAllowedMultisell(player, arguments);
            }
            case "BossStatus" -> {
                IVoicedCommandHandler command = VoicedCommandHandler.getInstance().getHandler("raid");
                if (command != null) {
                    command.useVoicedCommand("raid", player, "");
                }
            }
            case "donate" -> CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsgetfav_add");
            case "statistic" -> CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsclan");
            case "Interfaceemail" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("email");
                if (commands != null) {
                    commands.useVoicedCommand("email", player, "");
                }
            }
            case "PremiumStatus" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("premium");
                if (commands != null) {
                    commands.useVoicedCommand("premium", player, "");
                }
            }
            case "EpicStatus" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("epic");
                if (commands != null) {
                    commands.useVoicedCommand("epic", player, "");
                }
            }
            case "SkinStatus" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("skin");
                if (commands != null) {
                    commands.useVoicedCommand("skin", player, "");
                }
            }
            case "TopEnchant" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("topenchant");
                if (commands != null) {
                    commands.useVoicedCommand("topenchant", player, "");
                }
            }
            case "TourStatus" -> {
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("tour");
                if (commands != null) {
                    commands.useVoicedCommand("tour", player, "");
                }
            }
            default -> showMainMenu(player);
        }
    }

    /**
     * Retorna a lista de comandos de voz registrados pelo mod.
     */
    @Override
    public String[] getVoicedCommandList() {
        return new String[] { "donate", "bstatus" };
    }

    /**
     * Executa comandos de voz associados ao menu e painel de estatisticas.
     */
    @Override
    public boolean useVoicedCommand(String command, Player player, String target) {
        if (command.equalsIgnoreCase("donate")) {
            handleBypass(player, "donate");
            return true;
        } else if (command.equalsIgnoreCase("bstatus")) {
            handleBypass(player, "statistic");
            return true;
        }
        return false;
    }

    /**
     * Abre a janela nativa de variacao e augment de itens do cliente.
     */
    private void handleAugmentOpen(Player player) {
        if (player == null) {
            return;
        }
        player.sendPacket(SystemMessageId.SELECT_THE_ITEM_TO_BE_AUGMENTED);
        player.sendPacket(ExShowVariationMakeWindow.STATIC_PACKET);
    }

    /**
     * Processa parametros de ativacao ou desativacao de Soulshots automaticos.
     */
    private void handleRequestAutoShot(Player player, String command) {
        try {
            String params = command.substring("RequestAutoShot:".length()).trim();
            var parts = params.split(" ");
            int shotId = -1;
            boolean enable = false;

            for (var part : parts) {
                if (part.startsWith("ShotID=")) {
                    shotId = Integer.parseInt(part.substring(7));
                } else if (part.startsWith("bEnable=")) {
                    enable = Integer.parseInt(part.substring(8)) == 1;
                }
            }

            if (shotId != -1) {
                setAutoShotState(player, shotId, enable);
            }
        } catch (Exception e) {
            LOGGER.warn("[" + getName() + "] Falha ao interpretar RequestAutoShot: " + command, e);
        }
    }

    /**
     * Controla o estado de ativacao de Soulshots no inventario do jogador.
     */
    private void setAutoShotState(Player player, int shotId, boolean enable) {
        if (player.isInStoreMode() || player.isDead()) {
            return;
        }

        final var item = player.getInventory().getItemByItemId(shotId);
        if (item == null || !item.getItem().isShot()) {
            return;
        }

        boolean isActive = player.getAutoSoulShot().contains(shotId);

        if (enable && !isActive) {
            player.addAutoSoulShot(shotId);
            player.sendPacket(new ExAutoSoulShot(shotId, 0));
            player.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.YOU_FEEL_S1_EFFECT).addItemName(item));
            player.sendMessage("Ativado com sucesso: " + item.getItem().getName());
            useShot(player, item);
        } else if (!enable && isActive) {
            player.removeAutoSoulShot(shotId);
            player.sendPacket(new ExAutoSoulShot(shotId, 0));
            player.sendMessage("Desativado com sucesso: " + item.getItem().getName());
        }
    }

    /**
     * Executa o disparo inicial do Soulshot selecionado.
     */
    private void useShot(Player player, ItemInstance item) {
        final IItemHandler handler = ItemHandler.getInstance().getHandler(item.getItem());
        if (handler != null) {
            handler.useItem(player, item, false);
        }
    }

    /**
     * Alterna o estado do autofarm ou abre a pagina de configuracao de habilidades.
     */
    private void handleAutoFarm(Player player, String command) {
        String prefix = command.startsWith("_") ? "_autofarm" : "autofarm";
        String params = command.substring(prefix.length()).trim();

        if (prefix.equals("_autofarm") && params.isEmpty()) {
            AutoFarmManager.getInstance().toggleFarmStatus(player);
            player.sendPacket(ActionFailed.STATIC_PACKET);
        } else {
            AutoFarmManager.getInstance().handleBypass(player, params);
        }
    }

    /**
     * Ajusta o raio de acao do autofarm e exibe um cilindro colorido temporario para o jogador.
     */
    private void handleRadiusAutoFarm(Player player, String command) {
        var st = command.split(" ");
        if (st.length > 1) {
            final String action = st[1];
            final var autoFarmProfile = AutoFarmManager.getInstance().getProfile(player);

            if (autoFarmProfile.getSelectedArea() == null || autoFarmProfile.getSelectedArea().getType() == AutoFarmType.ZONA) {
                AutoFarmManager.getInstance().showIndexWindow(player, "Radius cannot be changed for this area type.");
                return;
            }

            int currentRadius = autoFarmProfile.getFinalRadius();
            int newRadius = switch (action) {
                case "inc_radius" -> Math.min(currentRadius + 100, autoFarmProfile.getAreaMaxRadius());
                case "dec_radius" -> Math.min(currentRadius - 100, autoFarmProfile.getAreaMaxRadius());
                default -> currentRadius;
            };

            newRadius = Math.max(100, Math.min(newRadius, 1500));
            autoFarmProfile.setRadius(newRadius);
            ZoneBuilder.getInstance().previewCylinder(player, autoFarmProfile.getFinalRadius());

            Color[] colors = {
                Color.YELLOW, Color.RED, Color.GREEN, Color.ORANGE, 
                Color.CYAN, Color.YELLOW, Color.ORANGE, Color.CYAN,
                Color.GREEN, Color.ORANGE
            };

            _virtualThreadExecutor.execute(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    long endTime = startTime + 3000; 
                    int colorIndex = 0;

                    while (System.currentTimeMillis() < endTime) {
                        if (!player.isOnline()) {
                            break; 
                        }

                        Color currentColor = colors[colorIndex % colors.length];
                        ZoneBuilder.getInstance().previewCylinder(player, autoFarmProfile.getFinalRadius(), currentColor);
                        colorIndex++;
                        Thread.sleep(30);
                    }

                    if (player.isOnline()) {
                        ZoneBuilder.getInstance().clearCylinderPreview(player);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        player.sendPacket(ActionFailed.STATIC_PACKET);
    }

    /**
     * Valida em O(log N) se a multisell solicitada esta autorizada no INI e comanda sua abertura.
     */
    private void openAllowedMultisell(Player player, String multisellIdStr) {
        int multisellId;
        try {
            multisellId = Integer.parseInt(multisellIdStr);
        } catch (NumberFormatException e) {
            player.sendMessage("ID de multisell invalido.");
            return;
        }

        boolean allowed = _allowedMultisells.length > 0 && Arrays.binarySearch(_allowedMultisells, multisellId) >= 0;

        if (allowed) {
            try {
                String multisellCommand = "_bbsmultisell;_maillist_0_1_0_;" + multisellId;
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), multisellCommand);
            } catch (Exception e) {
                LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao abrir multisell " + multisellId + " para " + player.getName(), e);
                player.sendMessage("Erro ao abrir a loja. Tente novamente.");
            }
        } else {
            LOGGER.warn("[" + getName() + "] Jogador " + player.getName() + " tentou abrir multisell nao permitida: " + multisellId);
            player.sendMessage("Este servico nao esta disponivel.");
            showMainMenu(player);
        }
    }

    /**
     * Extrai rapidamente o identificador numerico do teleporte sem compilacao de regex.
     */
    public static String parseTeleportIdFast(String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        int len = command.length();
        int start = 0;
        while (start < len && (command.charAt(start) < '0' || command.charAt(start) > '9')) {
            start++;
        }
        int end = start;
        while (end < len && command.charAt(end) >= '0' && command.charAt(end) <= '9') {
            end++;
        }
        return (start < end) ? command.substring(start, end) : "";
    }

    /**
     * Processa a cobranca e regras de negocio para o teleporte selecionado.
     */
    public void handleTeleportRequest(Player player, String command) {
        final String teleportId = parseTeleportIdFast(command);
        final var teleLocation = TeleportLocationData.getInstance().getTeleportLocation(teleportId);

        if (teleLocation.isEmpty()) {
            LOGGER.warn("[" + getName() + "] Teleport ID '" + teleportId + "' nao encontrado.");
            player.sendMessage("Local de teleporte invalido.");
            return;
        }

        final var location = teleLocation.get();
        if (!canTeleport(player, location)) {
            return;
        }

        int price = (player.getStatus().getLevel() >= 52) ? location.price() : 0;
        if (price > 0 && !player.destroyItemByItemId(57, price, true)) {
            player.sendPacket(SystemMessageId.YOU_NOT_ENOUGH_ADENA);
            return;
        }

        startTeleport(player, new Location(location.x(), location.y(), location.z()));
        player.sendPacket(ActionFailed.STATIC_PACKET);
    }

    /**
     * Valida restricoes de combate, karma, olimpíadas e nobreza antes de permitir teleporte.
     */
    private boolean canTeleport(Player player, TeleportLocation location) {
        if (_teleportCooldowns.getOrDefault(player.getObjectId(), 0L) > System.currentTimeMillis()) {
            player.sendMessage("Voce deve esperar para usar o teleporte novamente.");
            return false;
        }
        if (player.getDungeon() != null) {
            player.sendMessage("Voce esta em uma dungeon.");
            return false;
        }

        String restrictionReason = switch (player) {
            case Player p when p.isDead() -> "Voce nao pode se teleportar enquanto esta morto.";
            case Player p when p.isInOlympiadMode() -> "Voce nao pode se teleportar durante uma Olimpiada.";
            case Player p when p.getCast().isCastingNow() || p.isImmobilized() -> "Voce nao pode se teleportar enquanto esta conjurando ou imobilizado.";
            case Player p when p.isInCombat() -> "Voce nao pode se teleportar em combate.";
            case Player p when p.isInDuel() -> "Voce nao pode se teleportar durante um duelo.";
            case Player p when p.getPvpFlag() > 0 -> "Voce nao pode se teleportar com a flag de PvP ativa.";
            case Player p when p.getKarma() > 0 -> "Voce nao pode se teleportar com karma.";
            case Player p when p.isInJail() -> "Voce nao pode se teleportar na prisao.";
            case Player p when location.isNoble() && !p.isNoble() -> "Apenas nobres podem ir para esta zona.";
            default -> null;
        };

        if (restrictionReason != null) {
            player.sendMessage(restrictionReason);
            return false;
        }
        return true;
    }

    /**
     * Executa a animacao com barra de gauge e efetua o teleporte ao final do tempo.
     */
    private void startTeleport(Player player, Location location) {
        _teleportCooldowns.put(player.getObjectId(), System.currentTimeMillis() + _teleportCastTime);

        final var teleportSkill = SkillTable.getInstance().getInfo(_teleportSkillAnimationId, 1);
        if (teleportSkill == null) {
            LOGGER.warn("[" + getName() + "] Skill de teleporte com ID " + _teleportSkillAnimationId + " nao encontrada.");
            player.sendMessage("Erro interno no teleporte.");
            return;
        }

        player.getAI().tryToCast(player, teleportSkill, false, false, 0);
        player.broadcastPacket(new MagicSkillUse(player, player, 2013, 1, _teleportCastTime, 0));
        player.sendPacket(new SetupGauge(GaugeColor.BLUE, _teleportCastTime));
        player.sendMessage("Teleporte iniciado. Aguarde por " + _teleportCastTime / 1000 + " segundos.");

        _virtualThreadExecutor.execute(() -> {
            try {
                Thread.sleep(Duration.ofMillis(_teleportCastTime));
                if (!player.getCast().isCastingNow()) {
                    player.sendPacket(new CreatureSay(0, SayType.TELL, "Interface", "Seu teleporte foi cancelado."));
                    return;
                }
                player.getCast().stop();
                if (player.isDead() || !player.isOnline()) {
                    return;
                }
                player.teleToLocation(location);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[" + getName() + "] Thread de teleporte interrompida para o jogador " + player.getName(), e);
            }
        });
    }

    /**
     * Exibe o menu principal utilizando template HTML armazenado em cache na memoria.
     */
    private void showMainMenu(Player player) {
        try {
            final var htmlFile = new File("./data/locale/en_US/html/interface/index.html");
            if (!htmlFile.exists()) {
                LOGGER.warn("[" + getName() + "] Arquivo HTML nao encontrado: " + htmlFile.getAbsolutePath());
                player.sendMessage("Interface HTML file not found.");
                return;
            }

            long currentMtime = htmlFile.lastModified();
            if (_cachedHtmlTemplate == null || currentMtime != _lastHtmlMtime) {
                String raw = Files.readString(htmlFile.toPath());
                _cachedHtmlTemplate = prepareHtmlTemplate(raw);
                _lastHtmlMtime = currentMtime;
            }

            String html = _cachedHtmlTemplate.replace("%playerName%", player.getName());

            var npcHtmlMessage = new NpcHtmlMessage(0);
            npcHtmlMessage.setHtml(html);
            player.sendPacket(npcHtmlMessage);
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao carregar HTML para o jogador " + player.getName(), e);
            player.sendMessage("Failed to load interface.");
        }
    }

    /**
     * Ajusta os botoes de loja caso nao existam multisells autorizadas na configuracao.
     */
    private String prepareHtmlTemplate(String html) {
        if (_allowedMultisells.length == 0) {
            return html.replaceAll(
                "<button value=\"[^\"]*\" action=\"bypass -h voiced_interface Shop [0-9]+\"[^>]*>",
                "<button value=\"Loja Indisponivel\" action=\"bypass -h voiced_interface\" width=200 height=30 back=\"L2butom.bitbuttom8_over\" fore=\"L2butom.bitbuttom8\" disabled>"
            );
        }
        return html;
    }

    /**
     * Copia arquivos modelo embutidos no pacote JAR caso ainda nao existam no disco.
     */
    private void copyResourceIfNotExists(String resourcePath, String destinationPath) throws IOException {
        Path dest = Path.of(destinationPath);
        if (Files.notExists(dest)) {
            Files.createDirectories(dest.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
                 OutputStream out = Files.newOutputStream(dest)) {
                if (in == null) {
                    throw new IOException("Recurso nao encontrado no JAR: " + resourcePath);
                }
                in.transferTo(out);
                LOGGER.info("[" + getName() + "] Arquivo de configuracao padrao criado: " + destinationPath);
            }
        }
    }

    /**
     * Carrega as propriedades do arquivo INI e mantem o array de multisells ordenado.
     */
    private void loadConfigsFromIni() {
        final var settings = new Properties();
        try (var is = new FileInputStream(new File("./config/InterfaceConfig.ini"))) {
            settings.load(is);
            String multisellIds = settings.getProperty("AllowedMultisells", "");
            if (!multisellIds.isEmpty()) {
                _allowedMultisells = Arrays.stream(multisellIds.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .mapToInt(Integer::parseInt)
                        .toArray();
                Arrays.sort(_allowedMultisells);
            } else {
                _allowedMultisells = new int[0];
            }
            _cachedHtmlTemplate = null;
            _teleportCastTime = Integer.parseInt(settings.getProperty("TeleportCastTime", "15000"));
            _teleportSkillAnimationId = Integer.parseInt(settings.getProperty("TeleportSkillAnimationId", "2039"));
            LOGGER.info("[" + getName() + "] Carregadas " + _allowedMultisells.length + " multisells permitidas e teleporte.");
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao ler config/InterfaceConfig.ini.", e);
        }
    }

    /**
     * Leitor e repositorio dos pontos de teleporte definidos em arquivo XML.
     */
    private static final class TeleportLocationData implements IXmlReader {
        private final Map<String, TeleportLocation> _teleports = new ConcurrentHashMap<>();

        @Override
        public void load() {
            _teleports.clear();
            parseDataFile("custom/mods/teleportLocationsInterface.xml");
        }

        @Override
        public void parseDocument(Document doc, Path path) {
            forEach(doc, "list", listNode -> forEach(listNode, "teleport", teleportNode -> {
                final var set = new StatSet();
                final NamedNodeMap attrs = teleportNode.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    set.set(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
                }
                try {
                    final var loc = new TeleportLocation(set);
                    _teleports.put(loc.id(), loc);
                } catch (Exception e) {
                    LOGGER.warn(Level.WARNING, "Erro ao carregar teleport location do XML: " + set.getString("id", "UNKNOWN"), e);
                }
            }));
            LOGGER.info("[Interface_BrProject] Carregados " + _teleports.size() + " destinos de teleporte.");
        }

        public Optional<TeleportLocation> getTeleportLocation(String id) {
            return Optional.ofNullable(_teleports.get(id));
        }

        public static TeleportLocationData getInstance() {
            return SingletonHolder.INSTANCE;
        }

        private static class SingletonHolder {
            protected static final TeleportLocationData INSTANCE = new TeleportLocationData();
        }
    }

    /**
     * Registro imutavel com coordenadas 3D, preco e restricoes de um destino de teleporte.
     */
    public record TeleportLocation(String id, int price, boolean isNoble, int skillEffectId, int x, int y, int z) {
        public TeleportLocation(StatSet set) {
            this(set.getString("id"), set.getInteger("price"), set.getBool("isNoble"), set.getInteger("SkillEffectId"), set.getInteger("x"), set.getInteger("y"), set.getInteger("z"));
        }
    }
}
