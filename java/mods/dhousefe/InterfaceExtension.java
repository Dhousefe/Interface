package mods.dhousefe;

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
import java.util.stream.Stream;

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
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmProfile;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager.AutoFarmType;
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
import ext.mods.gameserver.skills.L2Skill;

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

    private final Map<Integer, Long> _teleportCooldowns = new ConcurrentHashMap<>();
    private final ExecutorService _virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void onLoad() {
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
                    LOGGER.warn("[" + getName() + "] Bypass BuffEngine_Dispel com skillId inválido: " + p[1]);
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
            case "donate" -> {
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsgetfav_add");
            }
            case "statistic" -> {
                CustomCommunityBoard.getInstance().handleCommands(player.getClient(), "_bbsclan");
            }
            default -> showMainMenu(player);
        }
    }


    @Override
    public String[] getVoicedCommandList() {
        return new String[] { "donate", "bstatus" };
    }

    @Override
    public boolean useVoicedCommand(String command, Player player, String target)
    {
        LOGGER.info("[" + getName() + "] Received voiced command " + command + " from " + player.getName());

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
            if (command.startsWith("RequestAutoShot:")) {
                handleRequestAutoShot(player, command);
            } else if (command.startsWith("GkGo ")) {
                handleTeleportRequest(player, command);
            } else if (command.startsWith("BuffEngine_Dispel")) {
                handleBypass(player, command);
            } else if (command.startsWith("autofarm") || command.startsWith("_autofarm")) {
                handleAutoFarm(player, command);
            } else if (command.equals("_infosettings")) {
                AutoFarmManager.getInstance().handleBypass(player, "skills page 1");
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
                final String actualCommand = command.substring(BYPASS_PREFIX.length()).trim();
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
        // Only handle commands that start with our BYPASS_PREFIX or specific known commands
        LOGGER.info("[" + getName() + "] Received bypass from " + player.getName() + ": " + command);
        if (command.startsWith(BYPASS_PREFIX) ||
            command.startsWith("RequestAutoShot:") ||
            command.startsWith("GkGo ") ||
            command.startsWith("BuffEngine_Dispel") ||
            command.startsWith("autofarm") ||
            command.startsWith("_autofarm") ||
            command.equals("_infosettings") ||
            command.startsWith("_radiusAutoFarm") ||
            command.equals("_daniloAugment") ||
            //command.equals("donate") || 
            command.equals("raid") ||
            command.equals("bstatus") ||
            command.equals("bp_openhtml mods/lucky/40079.htm")) {
            
            _virtualThreadExecutor.execute(() -> handleCommandAsync(player, command));
            
            return true; // Indicate that this bypass was handled by this listener
        }
        
        return false; // Indicate that this bypass was not handled by this listener

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
            AutoFarmManager.getInstance().toggleFarmStatus(player);
            player.sendPacket(ActionFailed.STATIC_PACKET);
        } else {
            AutoFarmManager.getInstance().handleBypass(player, params);
        }
    }

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
                case "inc_radius" -> Math.max(currentRadius + 100, autoFarmProfile.getAreaMaxRadius());
                case "dec_radius" -> Math.min(currentRadius - 100, autoFarmProfile.getAreaMaxRadius());
                default -> currentRadius;
            };

            newRadius = Math.max(100, Math.min(newRadius, 1500));

            autoFarmProfile.setRadius(newRadius);
            AutoFarmManager.getInstance().handleBypass(player, "options");
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
        final String html = """
            <html>
                <head><title>Painel de Controle</title></head>
                <body>
                    <center>
                        <img src="L2UI_CH3.herotower_deco" width=256 height=32>
                        <br>
                        <h2>Bem-vindo, %playerName%!</h2>
                        <br>
                        <p>Selecione um serviço abaixo:</p>
                        <br>
                        <button value="Loja" action="bypass -h voiced_interface Shop" width=200 height=30 back="L2butom.bitbuttom8_over" fore="L2butom.bitbuttom8">
                        <button value="Serviços" action="bypass -h _bbsmemo" width=200 height=30 back="L2butom.bitbuttom8_over" fore="L2butom.bitbuttom8">
                        <button value="Status dos Bosses" action="bypass -h voiced_interface BossStatus" width=200 height=30 back="L2butom.bitbuttom8_over" fore="L2butom.bitbuttom8">
                        <br>
                        <img src="L2UI_CH3.herotower_deco" width=256 height=32>
                    </center>
                </body>
            </html>
            """;

        var npcHtmlMessage = new NpcHtmlMessage(0);
        npcHtmlMessage.setHtml(html.replace("%playerName%", player.getName()));
        player.sendPacket(npcHtmlMessage);
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
            MultisellData.getInstance().separateAndSend(String.valueOf(multisellId), player, null, false);
        } else {
            LOGGER.warn("Player " + player.getName() + " tentou abrir a multisell não permitida: " + multisellId);
            player.sendMessage("Este serviço não está disponível.");
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
            LOGGER.info("TeleportLocationData: Loaded " + _teleports.size() + " interface teleport locations.");
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
