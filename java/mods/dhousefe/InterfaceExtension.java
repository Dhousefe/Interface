package mods.dhousefe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
import java.util.stream.Stream;
import java.awt.Color;

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
import ext.mods.gameserver.data.xml.MultisellData;
import ext.mods.gameserver.enums.GaugeColor;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.handler.IItemHandler;
import ext.mods.gameserver.handler.IVoicedCommandHandler;
import ext.mods.gameserver.handler.ItemHandler;
import ext.mods.gameserver.handler.VoicedCommandHandler;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.util.CryptaManager;
import ext.mods.autofarm.AutoFarmProfile;
import ext.mods.autofarm.ZoneBuilder;
import ext.mods.autofarm.AutoFarmArea;
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
 * @author Dhousefe
 * Brproject (RusaCis 3.8)
 * Este mod foi refatorado para funcionar como uma extensão L2JExtension,
 * utilizando Java 21 e Project Loom para operações assíncronas.
 * Ele se registra dinamicamente para ouvir bypasses, eliminando a necessidade
 * de integrar-se diretamente ao core do servidor.
 */
public final class InterfaceExtension implements L2JExtension, OnBypassCommandListener, IVoicedCommandHandler {
    private static final CLogger LOGGER = new CLogger(InterfaceExtension.class.getName());
    private static final String BYPASS_PREFIX = "voiced_interface";

    private int _teleportCastTime = 15000;
    private int _teleportSkillAnimationId = 2039;
    private int[] _allowedMultisells = {};
    private Object _autoFarmManager = null;
    private boolean _autoFarmAvailable = false;

    private final Map<Integer, Long> _teleportCooldowns = new ConcurrentHashMap<>();
    private final ExecutorService _virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void onLoad() {
        try {
            copyResourceIfNotExists("mods/htmls/index.html", "./data/locale/en_US/html/interface/index.html");
            
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {
            copyResourceIfNotExists("mods/configs/InterfaceConfig.ini", "./config/InterfaceConfig.ini");
            
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {
            
            copyResourceIfNotExists("mods/xmls/teleportLocationsInterface.xml", "./data/custom/mods/teleportLocationsInterface.xml");
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }

        loadConfigsFromIni();
        TeleportLocationData.getInstance().load();
        BypassCommandManager.getInstance().registerBypassListener(this);
        VoicedCommandHandler.getInstance().registerHandler(this);
        initializeAutoFarmManager();
        LOGGER.info("[" + getName() + "] Carregado e registrado com sucesso.");
    }

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

    @Override
    public String getName() {
        return "Interface_BrProject";
    }

    /**
     * Inicializa o AutoFarmManager
     */
    private void initializeAutoFarmManager() {
        try {
            // Inicializar CryptaManager se necessário
            CryptaManager.initialize();
            
            // Obter instância do AutoFarmManager
            _autoFarmManager = CryptaManager.getAutoFarmManager();
            
            if (_autoFarmManager != null) {
                // Testar se o AutoFarmManager está funcionando
                if (testAutoFarmManager()) {
                    _autoFarmAvailable = true;
                    //LOGGER.info("[" + getName() + "] AutoFarmManager carregado e testado com sucesso via CryptaManager");
                } else {
                    LOGGER.warn("[" + getName() + "] AutoFarmManager carregado mas não está funcionando corretamente");
                }
            } else {
                LOGGER.warn("[" + getName() + "] Falha ao carregar AutoFarmManager via CryptaManager");
            }
            
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao inicializar AutoFarmManager: " + e.getMessage(), e);
        }
    }

    private boolean testAutoFarmManager() {
        try {
            // Testar método básico
            Method getInstanceMethod = _autoFarmManager.getClass().getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            return instance != null;
        } catch (Exception e) {
            LOGGER.warn("[" + getName() + "] Teste do AutoFarmManager falhou: " + e.getMessage());
            return false;
        }
    }

    /**
     * Obtém instância do AutoFarmManager via CryptaManager
     */
    private Object getAutoFarmManager() {
        if (_autoFarmManager == null) {
            initializeAutoFarmManager();
        }
        return _autoFarmManager;
    }

    private boolean isAutoFarmAvailable() {
        return _autoFarmAvailable && _autoFarmManager != null;
    }

    /**
     * Executa método do AutoFarmManager via reflexão
     */
    private Object executeAutoFarmMethod(String methodName, Object... args) {
        if (!isAutoFarmAvailable()) {
            LOGGER.warn("[" + getName() + "] AutoFarmManager não disponível para método: " + methodName);
            return null;
        }

        try {
            Object manager = getAutoFarmManager();
            if (manager == null) {
                return null;
            }

            // Determinar tipos dos argumentos
            Class<?>[] paramTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null) {
                    paramTypes[i] = args[i].getClass();
                } else {
                    paramTypes[i] = Object.class;
                }
            }

            // Tentar encontrar o método
            Method method = null;
            try {
                method = manager.getClass().getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                // Tentar com tipos mais genéricos
                Method[] methods = manager.getClass().getMethods();
                for (Method m : methods) {
                    if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                        method = m;
                        break;
                    }
                }
            }

            if (method == null) {
                LOGGER.warn("[" + getName() + "] Método " + methodName + " não encontrado no AutoFarmManager");
                return null;
            }

            // Executar método via reflexão
            return method.invoke(manager, args);

        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao executar método " + methodName + " do AutoFarmManager: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Obtém perfil do AutoFarm via reflexão
     */
    private Object getAutoFarmProfile(Player player) {
        if (!isAutoFarmAvailable()) {
            return null;
        }

        try {
            Object manager = getAutoFarmManager();
            if (manager == null) {
                return null;
            }

            // Tentar diferentes assinaturas do método getProfile
            Method method = null;
            try {
                method = manager.getClass().getMethod("getProfile", Player.class);
            } catch (NoSuchMethodException e) {
                // Tentar com Integer
                try {
                    method = manager.getClass().getMethod("getProfile", Integer.class);
                    return method.invoke(manager, player.getObjectId());
                } catch (NoSuchMethodException e2) {
                    // Tentar getPlayer
                    try {
                        method = manager.getClass().getMethod("getPlayer", Integer.class);
                        return method.invoke(manager, player.getObjectId());
                    } catch (NoSuchMethodException e3) {
                        LOGGER.warn("[" + getName() + "] Nenhum método de perfil encontrado no AutoFarmManager");
                        return null;
                    }
                }
            }

            return method.invoke(manager, player);

        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao obter perfil do AutoFarm: " + e.getMessage(), e);
            return null;
        }
    }

    public void handleBypass(Player player, String bypass) {
        //LOGGER.info("[" + getName() + "] handleBypass called with: '" + bypass + "'");
        
        var parts = bypass.split(" ", 2);
        var action = parts[0];
        var arguments = parts.length > 1 ? parts[1] : "";
        
        //LOGGER.info("[" + getName() + "] Action: '" + action + "', Arguments: '" + arguments + "'");

        if (action.startsWith("BuffEngine_Dispel")) {
            var p = action.split("=");
            if (p.length == 2) {
                try {
                    int skillId = Integer.parseInt(p[1]);
                    player.stopSkillEffects(skillId);
                } catch (NumberFormatException e) {
                    LOGGER.warn("[" + getName() + "] Bypass BuffEngine_Dispel com skillId inválido: " + p[1]);
                }
            }
            return;
        }

        switch (action) {
            case "GkGo" -> {
                //LOGGER.info("[" + getName() + "] Processing GkGo command");
                if (!arguments.isEmpty()) handleTeleportRequest(player, arguments);
            }
            case "Shop" -> {
                //LOGGER.info("[" + getName() + "] Processing Shop command with arguments: " + arguments);
                if (!arguments.isEmpty()) openAllowedMultisell(player, arguments);
            }
            case "BossStatus" -> {
                //LOGGER.info("[" + getName() + "] Processing BossStatus command");
                IVoicedCommandHandler command = VoicedCommandHandler.getInstance().getHandler("raid");
                if (command != null) {
                    command.useVoicedCommand("raid", player, "");
                }
            }
            case "donate" -> {
                //LOGGER.info("[" + getName() + "] Processing donate command");
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsgetfav_add");
            }
            case "statistic" -> {
                //LOGGER.info("[" + getName() + "] Processing statistic command");
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsclan");
            }
            case "Interfaceemail" -> {
                //LOGGER.info("[" + getName() + "] Processing email command");
                IVoicedCommandHandler commands = VoicedCommandHandler.getInstance().getHandler("email");
                if (commands != null) {
                    commands.useVoicedCommand("email", player, "");
                }
            }
            default -> {
                //LOGGER.info("[" + getName() + "] No matching action found, showing main menu");
                showMainMenu(player);
            }
        }
    }


    @Override
    public String[] getVoicedCommandList() {
        return new String[] { "donate", "bstatus"};
    }

    @Override
    public boolean useVoicedCommand(String command, Player player, String target)
    {
        //LOGGER.info("[" + getName() + "] Received voiced command " + command + " from " + player.getName());

        if (command.equalsIgnoreCase("donate")) {
        handleBypass(player, "donate");
        return true;
        } else if (command.equalsIgnoreCase("bstatus")) {
        handleBypass(player, "statistic");
        return true;
        } 
    
    // If it's not the .donate command, let the default handler take over
    return false;
    }

    private void handleCommandAsync(Player player, String command) {
        try {
            //LOGGER.info("[" + getName() + "] handleCommandAsync processing: " + command);
            
            // Não processar comandos de email aqui para evitar loops
            if (command.equals("email") || command.equals("voiced_interface Interfaceemail")) {
                return;
            }
            
            if (command.startsWith("RequestAutoShot:")) {
                handleRequestAutoShot(player, command);
            } else if (command.startsWith("GkGo ")) {
                handleTeleportRequest(player, command);
            } else if (command.startsWith("BuffEngine_Dispel")) {
                handleBypass(player, command);
            } else if (command.startsWith("autofarm") || command.startsWith("_autofarm")) {
                handleAutoFarm(player, command);
            } else if (command.equals("_infosettings")) {
                 if (isAutoFarmAvailable()) {
                    executeAutoFarmMethod("handleBypass", player, "skills page 1");
                } else {
                    player.sendMessage("Sistema de AutoFarm não disponível no momento.");
                }
                
            } else if (command.startsWith("_radiusAutoFarm")) {
                handleRadiusAutoFarm(player, command);
            } else if (command.equals("_daniloAugment")) {
                handleAugmentOpen(player);
            } else if (command.startsWith("donate")) { 
                handleBypass(player, "_bbsgetfav_add");
            } else if (command.startsWith("bstatus")) { 
                handleBypass(player, "statistic");
            } else if (command.equals("bp_openhtml mods/lucky/40079.htm")) { 
                handleBypass(player, ".raid");
            } else if (command.startsWith(BYPASS_PREFIX)) {
                //LOGGER.info("[" + getName() + "] Processing voiced_interface command: " + command);
                final String actualCommand = command.substring(BYPASS_PREFIX.length()).trim();
                //LOGGER.info("[" + getName() + "] Extracted actual command: '" + actualCommand + "'");
                handleBypass(player, actualCommand);
            } else {
                LOGGER.warn("[" + getName() + "] Unknown or unhandled bypass command '" + command + "' from player " + player.getName());
                player.sendPacket(ActionFailed.STATIC_PACKET);
            }
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Error processing command '" + command + "' for player " + player.getName(), e);
        }
    }
    
    
    @Override
    public boolean onBypass(Player player, String command) {
        
        //LOGGER.info("[" + getName() + "] Received bypass from " + player.getName() + ": '" + command + "'");
        //LOGGER.info("[" + getName() + "] BYPASS_PREFIX: '" + BYPASS_PREFIX + "'");
        //LOGGER.info("[" + getName() + "] Command starts with BYPASS_PREFIX: " + command.startsWith(BYPASS_PREFIX));
        
        if (command.startsWith(BYPASS_PREFIX) ||
            command.startsWith("RequestAutoShot:") ||
            command.startsWith("GkGo ") ||
            command.startsWith("BuffEngine_Dispel") ||
            command.startsWith("autofarm") ||
            command.startsWith("_autofarm") ||
            command.equals("_infosettings") ||
            command.startsWith("_radiusAutoFarm") ||
            command.equals("_daniloAugment") ||
            command.equals("raid") ||
            command.equals("bstatus") ||
            command.equals("email") ||
            command.equals("bp_openhtml mods/lucky/40079.htm")) {
            
            
            if (command.equals("voiced_interface Interfaceemail") || command.equals("email")) {
                handleBypass(player, "Interfaceemail");
            } else {
                _virtualThreadExecutor.execute(() -> handleCommandAsync(player, command));
            }
            
            return true; // Indicate that this bypass was handled by this listener
        }
        
       
        return false; 
    }

    private void handleAugmentOpen(Player player) {
        if (player == null) {
            return;
        }
        player.sendPacket(SystemMessageId.SELECT_THE_ITEM_TO_BE_AUGMENTED);
        player.sendPacket(ExShowVariationMakeWindow.STATIC_PACKET);
    }

    
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
            LOGGER.warn("[" + getName() + "] Could not parse RequestAutoShot bypass: " + command, e);
        }
    }

    private void handleAutoFarm(Player player, String command) {
        String prefix = command.startsWith("_") ? "_autofarm" : "autofarm";
        String params = command.substring(prefix.length()).trim();

        if (prefix.equals("_autofarm") && params.isEmpty()) {
            executeAutoFarmMethod("toggleFarmStatus", player);
            player.sendPacket(ActionFailed.STATIC_PACKET);
        } else {
            executeAutoFarmMethod("handleBypass", player, params);
        }
    }

    private void handleRadiusAutoFarm(Player player, String command) {
    var st = command.split(" ");
    if (st.length > 1) {
        final String action = st[1];
        
        // Obter AutoFarmProfile via CryptaManager (apenas AutoFarmManager é criptografado)
        AutoFarmProfile autoFarmProfile = (AutoFarmProfile) ext.mods.util.CryptaManager.executeMethod("AutoFarmManager", "getProfile", player);
        
        if (autoFarmProfile == null) {
            LOGGER.warn("[" + getName() + "] AutoFarmProfile não disponível para player: " + player.getName());
            return;
        }

        try {
            // Obter área selecionada diretamente
            AutoFarmArea selectedArea = autoFarmProfile.getSelectedArea();
            
            if (selectedArea == null) {
                ext.mods.util.CryptaManager.executeMethod("AutoFarmManager", "showIndexWindow", player, "Radius cannot be changed for this area type.");
                return;
            }

            // Verificar tipo da área diretamente
            Object areaType = selectedArea.getType();
            
            // Verificar se é ZONA
            if (areaType.toString().equals("ZONA")) {
                ext.mods.util.CryptaManager.executeMethod("AutoFarmManager", "showIndexWindow", player, "Radius cannot be changed for this area type.");
                return;
            }

            // Obter raio atual e máximo diretamente
            int currentRadius = autoFarmProfile.getFinalRadius();
            int maxRadius = autoFarmProfile.getAreaMaxRadius();

            int newRadius = switch (action) {
                case "inc_radius" -> Math.min(currentRadius + 100, maxRadius);
                case "dec_radius" -> Math.max(currentRadius - 100, 100);
                default -> currentRadius;
            };

            newRadius = Math.max(100, Math.min(newRadius, 1500));

            // Definir novo raio diretamente
            autoFarmProfile.setRadius(newRadius);

            // Atualizar preview do cilindro diretamente
            ZoneBuilder zoneBuilder = ZoneBuilder.getInstance();
            zoneBuilder.previewCylinder(player, newRadius);
            
            // Array de cores para alternar
            java.awt.Color[] colors = {
                java.awt.Color.YELLOW, java.awt.Color.RED, java.awt.Color.GREEN, java.awt.Color.MAGENTA, 
                java.awt.Color.YELLOW, java.awt.Color.MAGENTA, java.awt.Color.ORANGE, java.awt.Color.PINK,
                java.awt.Color.RED, java.awt.Color.GREEN
            };
            
            // Atualizar o cilindro a cada 30ms por 3 segundos com cores alternadas
            _virtualThreadExecutor.execute(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    long endTime = startTime + 3000; 
                    int colorIndex = 0;
                    
                    while (System.currentTimeMillis() < endTime) {
                        if (!player.isOnline()) {
                            break; 
                        }
                        
                        // Obter raio atual diretamente
                        int currentRadiusValue = autoFarmProfile.getFinalRadius();
                        
                        // Atualizar preview com cor alternada
                        java.awt.Color currentColor = colors[colorIndex % colors.length];
                        
                        // Tentar método com cor se disponível
                        try {
                            zoneBuilder.previewCylinder(player, currentRadiusValue, currentColor);
                        } catch (Exception e) {
                            // Fallback para método sem cor
                            zoneBuilder.previewCylinder(player, currentRadiusValue);
                        }
                        
                        colorIndex++;
                        
                        Thread.sleep(30);
                    }
                    
                    if (player.isOnline()) {
                        zoneBuilder.clearCylinderPreview(player);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    //LOGGER.warn("[" + getName() + "] Timer de atualização do cilindro foi interrompido para o player " + player.getName(), e);
                } catch (Exception e) {
                    LOGGER.warn("[" + getName() + "] Erro ao atualizar cilindro: " + e.getMessage(), e);
                }
            });
            
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao processar _radiusAutoFarm: " + e.getMessage(), e);
        }
    }
    player.sendPacket(ActionFailed.STATIC_PACKET);
}

    private void copyResourceIfNotExists(String resourcePath, String destinationPath) throws IOException {
        Path dest = Path.of(destinationPath);
        if (Files.notExists(dest)) {
            Files.createDirectories(dest.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
                 OutputStream out = Files.newOutputStream(dest)) {
                if (in == null) {
                    throw new IOException("Recurso não encontrado no JAR: " + resourcePath);
                }
                in.transferTo(out);
                LOGGER.info("[" + getName() + "] Arquivo de configuração padrão criado: " + destinationPath);
            }
        }
    }

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
            }
            _teleportCastTime = Integer.parseInt(settings.getProperty("TeleportCastTime", "15000"));
            _teleportSkillAnimationId = Integer.parseInt(settings.getProperty("TeleportSkillAnimationId", "2039"));
            LOGGER.info("[" + getName() + "] Loaded " + _allowedMultisells.length + " allowed multisells and teleport settings.");
        } catch (Exception e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Failed to load config/CustomMods/InterfaceConfig.ini.", e);
        }
    }

    private void showMainMenu(Player player) {
        try {
            final var htmlFile = new File("./data/locale/en_US/html/interface/index.html");
            if (!htmlFile.exists()) {
                LOGGER.warn("[" + getName() + "] HTML file not found: " + htmlFile.getAbsolutePath());
                player.sendMessage("Interface HTML file not found.");
                return;
            }
            
            String html = Files.readString(htmlFile.toPath());
            html = html.replace("%playerName%", player.getName());
            
            // Adicionar validação de multisells permitidas
            html = validateAndUpdateMultisellButtons(html, player);
            
            var npcHtmlMessage = new NpcHtmlMessage(0);
            npcHtmlMessage.setHtml(html);
            player.sendPacket(npcHtmlMessage);
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Failed to load HTML file for player " + player.getName(), e);
            player.sendMessage("Failed to load interface.");
        }
    }

    // Novo método para validar e atualizar botões de multisell
    private String validateAndUpdateMultisellButtons(String html, Player player) {
        // Verificar se o player tem permissão para acessar multisells
        if (_allowedMultisells.length == 0) {
            // Se não há multisells permitidas, remover ou desabilitar botões de shop
            html = html.replaceAll(
                "<button value=\"[^\"]*\" action=\"bypass -h voiced_interface Shop [0-9]+\"[^>]*>",
                "<button value=\"Loja Indisponível\" action=\"bypass -h voiced_interface\" width=200 height=30 back=\"L2butom.bitbuttom8_over\" fore=\"L2butom.bitbuttom8\" disabled>"
            );
        } else {
            // Validar se os botões no HTML correspondem às multisells permitidas
            for (int multisellId : _allowedMultisells) {
                String buttonPattern = "bypass -h voiced_interface Shop " + multisellId;
                if (!html.contains(buttonPattern)) {
                    LOGGER.warn("[" + getName() + "] Multisell ID " + multisellId + " está permitida mas não tem botão no HTML");
                }
            }
        }
        
        return html;
    }

       
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
            //player.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.CANNOT_USE_SOULSHOTS).addItemName(item));
            player.sendMessage("Desativado com sucesso: " + item.getItem().getName());
        }
    }

    private void useShot(Player player, ItemInstance item) {
        final IItemHandler handler = ItemHandler.getInstance().getHandler(item.getItem());
        if (handler != null) {
            handler.useItem(player, item, false);
        }
    }

    private void openAllowedMultisell(Player player, String multisellIdStr) {
        int multisellId;
        try {
            multisellId = Integer.parseInt(multisellIdStr);
        } catch (NumberFormatException e) {
            player.sendMessage("ID de multisell inválido.");
            return;
        }

        boolean allowed = Arrays.stream(_allowedMultisells).anyMatch(id -> id == multisellId);

        if (allowed) {
            try {
                // Usar o mesmo método que CustomCommunityBoard usa
                String multisellCommand = "_bbsmultisell;_maillist_0_1_0_;" + multisellId;
                //LOGGER.info("[" + getName() + "] Abrindo multisell " + multisellId + " para " + player.getName() + " usando comando: " + multisellCommand);
                
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), multisellCommand);
                
                //LOGGER.info("[" + getName() + "] Player " + player.getName() + " abriu multisell " + multisellId);
            } catch (Exception e) {
                LOGGER.warn(Level.SEVERE, "[" + getName() + "] Erro ao abrir multisell " + multisellId + " para " + player.getName(), e);
                player.sendMessage("Erro ao abrir a loja. Tente novamente.");
            }
        } else {
            LOGGER.warn("[" + getName() + "] Player " + player.getName() + " tentou abrir a multisell não permitida: " + multisellId);
            player.sendMessage("Este serviço não está disponível.");
            // Opcional: redirecionar de volta para o menu principal
            showMainMenu(player);
        }
    }

    public void handleTeleportRequest(Player player, String command) {
        final String teleportId = command.replaceAll("[^0-9]", "");
        final var teleLocation = TeleportLocationData.getInstance().getTeleportLocation(teleportId);

        if (teleLocation.isEmpty()) {
            LOGGER.warn("[" + getName() + "] Teleport ID '" + teleportId + "' não encontrado.");
            player.sendMessage("Local de teleporte inválido.");
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

    private boolean canTeleport(Player player, TeleportLocation location) {
        if (_teleportCooldowns.getOrDefault(player.getObjectId(), 0L) > System.currentTimeMillis()) {
            player.sendMessage("Você deve esperar para usar o teleporte novamente.");
            return false;
        }
        if (player.getDungeon() != null) {
            player.sendMessage("Você está em uma dungeon.");
            return false;
        }

        String restrictionReason = switch (player) {
            case Player p when p.isDead() -> "Você não pode se teleportar enquanto está morto.";
            case Player p when p.isInOlympiadMode() -> "Você não pode se teleportar durante uma Olimpíada.";
            case Player p when p.getCast().isCastingNow() || p.isImmobilized() -> "Você não pode se teleportar enquanto está conjurando ou imobilizado.";
            case Player p when p.isInCombat() -> "Você não pode se teleportar em combate.";
            case Player p when p.isInDuel() -> "Você não pode se teleportar durante um duelo.";
            case Player p when p.getPvpFlag() > 0 -> "Você não pode se teleportar com a flag de PvP ativa.";
            case Player p when p.getKarma() > 0 -> "Você não pode se teleportar com karma.";
            case Player p when p.isInJail() -> "Você não pode se teleportar na prisão.";
            case Player p when location.isNoble() && !p.isNoble() -> "Apenas nobres podem ir para esta zona.";
            default -> null;
        };

        if (restrictionReason != null) {
            player.sendMessage(restrictionReason);
            return false;
        }
        return true;
    }

    private void startTeleport(Player player, Location location) {
        _teleportCooldowns.put(player.getObjectId(), System.currentTimeMillis() + _teleportCastTime);

        final var teleportSkill = SkillTable.getInstance().getInfo(_teleportSkillAnimationId, 1);
        if (teleportSkill == null) {
            LOGGER.warn("[" + getName() + "] Skill de teleporte com ID " + _teleportSkillAnimationId + " não encontrada.");
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
                LOGGER.warn("[" + getName() + "] Teleport thread was interrupted for player " + player.getName(), e);
            }
        });
    }

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
            LOGGER.info("[Interface_BrProject] Loaded " + _teleports.size() + " interface teleport locations.");
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

    public record TeleportLocation(String id, int price, boolean isNoble, int skillEffectId, int x, int y, int z) {
        public TeleportLocation(StatSet set) {
            this(set.getString("id"), set.getInteger("price"), set.getBool("isNoble"), set.getInteger("SkillEffectId"), set.getInteger("x"), set.getInteger("y"), set.getInteger("z"));
        }
    }

    
}
