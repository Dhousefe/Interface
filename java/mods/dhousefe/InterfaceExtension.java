package mods.dhousefe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;

import ext.mods.commons.data.StatSet;
import ext.mods.commons.data.xml.IXmlReader;
import ext.mods.commons.logging.CLogger;
import ext.mods.commons.random.Rnd;
import ext.mods.extensions.interfaces.L2JExtension;
import ext.mods.extensions.listener.command.OnBypassCommandListener;
import ext.mods.extensions.listener.manager.BypassCommandManager;
import ext.mods.Config;
import ext.mods.gameserver.data.SkillTable;
import ext.mods.gameserver.data.xml.MultisellData;
import ext.mods.gameserver.enums.GaugeColor;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.enums.items.ShotType;
import ext.mods.gameserver.handler.IItemHandler;
import ext.mods.gameserver.handler.IVoicedCommandHandler;
import ext.mods.gameserver.handler.ItemHandler;
import ext.mods.gameserver.handler.VoicedCommandHandler;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmProfile;
import ext.mods.gameserver.model.entity.autofarm.AutoFarmManager.AutoFarmType;
import ext.mods.gameserver.model.item.instance.ItemInstance;
import ext.mods.gameserver.model.item.kind.Item;
import ext.mods.gameserver.model.item.kind.Item;
import ext.mods.gameserver.model.item.kind.Weapon;
import ext.mods.gameserver.model.location.Location;
import ext.mods.gameserver.network.SystemMessageId;
import ext.mods.gameserver.network.serverpackets.ActionFailed;
import ext.mods.gameserver.network.serverpackets.CreatureSay;
import ext.mods.gameserver.network.serverpackets.ExAutoSoulShot;
import ext.mods.gameserver.network.serverpackets.MagicSkillUse;
import ext.mods.gameserver.network.serverpackets.NpcHtmlMessage;
import ext.mods.gameserver.network.serverpackets.SetupGauge;
import ext.mods.gameserver.network.serverpackets.SystemMessage;
import ext.mods.gameserver.skills.L2Skill;

/**
 * @author Dhousefe 
 * Brproject (RusaCis 3.8)
 * * Este mod foi refatorado para funcionar como uma extensão L2JExtension.
 * Ele se registra dinamicamente para ouvir bypasses, eliminando a necessidade
 * de integrar-se diretamente ao core do servidor.
 */
public class InterfaceExtension implements L2JExtension, OnBypassCommandListener {
    private static final CLogger LOGGER = new CLogger(InterfaceExtension.class.getName());
    
    // Prefixo para os bypasses que esta extensão irá manipular.
    private static final String BYPASS_PREFIX = "voiced_interface";
    
    private int _teleportCastTime = 15000;
    private int _teleportSkillAnimationId = 2039;
    private int[] _allowedMultisells = {};
    
    // Mapa para armazenar o tempo de recarga do teleporte por jogador.
    private final Map<Integer, Long> _teleportCooldowns = new ConcurrentHashMap<>();
    
    // O método onLoad é o ponto de entrada da extensão.
    @Override
    public void onLoad() {
    	try {
            copyResourceIfNotExists("mods/configs/InterfaceConfig.ini", "./config/CustomMods/InterfaceConfig.ini");
            copyResourceIfNotExists("mods/xmls/teleportLocationsInterface.xml", "./data/custom/mods/teleportLocationsInterface.xml");
        } catch (IOException e) {
            LOGGER.warn(Level.SEVERE, "[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            // A extensão não pode funcionar sem seus arquivos, então paramos aqui.
            return;
        }
        
        // Carrega as configurações e dados necessários.
        loadConfigsFromIni();
        TeleportLocationData.getInstance().load();
        
        // Registra esta classe como um listener para bypasses.
        BypassCommandManager.getInstance().registerBypassListener(this);
        
        LOGGER.info("[" + getName() + "] Carregado e registrado com sucesso.");
    }
    
    // O método onDisable é chamado quando a extensão é descarregada.
    @Override
    public void onDisable() {
        // Remove o registro do listener para evitar memory leaks.
        BypassCommandManager.getInstance().unregisterBypassListener(this);
        
        LOGGER.info("[" + getName() + "] Descarregado.");
    }
    
    @Override
    public String getName() {
        return "Interface_BrProject";
    }
    
    /**
     * Este método é chamado pelo BypassCommandManager sempre que um bypass é acionado.
     */
    @Override
    public boolean onBypass(Player player, String command) {
        // [NOVO] Handler para o comando de auto-shot do cliente
        if (command.startsWith("RequestAutoShot:")) {
            try {
                String params = command.substring("RequestAutoShot:".length()).trim();
                String[] parts = params.split(" ");
                int shotId = -1;
                boolean enable = false;

                for (String part : parts) {
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
            return true;
        }
        
        if (command.startsWith("GkGo ")) {
            handleTeleportRequest(player, command);
            return true;
        }
        
        if (command.startsWith("BuffEngine_Dispel")) {
            handleBypass(player, command);
            return true;
        }
        
        if (command.startsWith("autofarm") || command.startsWith("_autofarm")) {
            String prefix = command.startsWith("_") ? "_autofarm" : "autofarm";
            String params = command.substring(prefix.length()).trim();
            
            if (prefix.equals("_autofarm") && params.isEmpty()) {
                AutoFarmManager.getInstance().toggleFarmStatus(player);
                player.sendPacket(ActionFailed.STATIC_PACKET);
            } else {
                AutoFarmManager.getInstance().handleBypass(player, params);
            }
            return true;
        }
        
        if (command.equals("_infosettings")) {
            AutoFarmManager.getInstance().handleBypass(player, "skills page 1");
            return true;
        }
        
        if (command.startsWith("_radiusAutoFarm")) {
            final StringTokenizer st = new StringTokenizer(command, " ");
            st.nextToken(); // Pula o comando "_radiusAutoFarm"
            
            if (st.hasMoreTokens()) {
                final String action = st.nextToken();
                final AutoFarmProfile autoFarmProfile = AutoFarmManager.getInstance().getProfile(player);
                
                if (autoFarmProfile.getSelectedArea() == null || autoFarmProfile.getSelectedArea().getType() == AutoFarmType.ZONA) {
                    AutoFarmManager.getInstance().showIndexWindow(player, "Radius cannot be changed for this area type.");
                    return true;
                }
                
                int currentRadius = autoFarmProfile.getFinalRadius();
                int newRadius = currentRadius;
                
                if (action.equals("inc_radius")) {
                    newRadius = Math.max(currentRadius + 100, autoFarmProfile.getAreaMaxRadius());
                } else if (action.equals("dec_radius")) {
                    newRadius = Math.min(currentRadius - 100, autoFarmProfile.getAreaMaxRadius());
                }
                
                autoFarmProfile.setRadius(newRadius);
                AutoFarmManager.getInstance().handleBypass(player, "options");
            }
            player.sendPacket(ActionFailed.STATIC_PACKET);
            return true;
        }
        
        if (command.startsWith("autoshot ")) {
            handleAutoShot(player, command);
            return true;
        }
        
        if (command.startsWith(BYPASS_PREFIX)) {
            final String actualCommand = command.substring(BYPASS_PREFIX.length()).trim();
            handleBypass(player, actualCommand);
            return true;
        }
        
        LOGGER.warn("[" + getName() + "] Unknown or unhandled bypass command '" + command + "' from player " + player.getName());
        player.sendPacket(ActionFailed.STATIC_PACKET); // Envia ActionFailed para não deixar o cliente esperando
        return false; // Retorna false se não for um bypass desta extensão.
    }
    
    private void copyResourceIfNotExists(String resourcePath, String destinationPath) throws IOException {
        File destFile = new File(destinationPath);
        if (!destFile.exists()) {
            destFile.getParentFile().mkdirs();
            
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
                 OutputStream out = new FileOutputStream(destFile)) {
                
                if (in == null) {
                    throw new IOException("Recurso não encontrado no JAR: " + resourcePath);
                }
                
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                LOGGER.info("[" + getName() + "] Arquivo de configuração padrão criado: " + destinationPath);
            }
        }
    }
    
    private void loadConfigsFromIni() {
        final Properties settings = new Properties();
        try (InputStream is = new FileInputStream(new File("./config/CustomMods/InterfaceConfig.ini"))) {
            settings.load(is);
            String multisellIds = settings.getProperty("AllowedMultisells", "");
            if (!multisellIds.isEmpty()) {
                String[] ids = multisellIds.split(",");
                _allowedMultisells = new int[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    _allowedMultisells[i] = Integer.parseInt(ids[i].trim());
                }
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
                        <button value="Teleportes" action="bypass -h voiced_interface Gk" width=200 height=30 back="L2UI_CT1.Button_DF_Down" fore="L2UI_CT1.Button_DF">
                        <button value="Loja" action="bypass -h voiced_interface Shop" width=200 height=30 back="L2UI_CT1.Button_DF_Down" fore="L2UI_CT1.Button_DF">
                        <button value="Serviços" action="bypass -h voiced_interface Services" width=200 height=30 back="L2UI_CT1.Button_DF_Down" fore="L2UI_CT1.Button_DF">
                        <button value="Status dos Bosses" action="bypass -h voiced_interface BossStatus" width=200 height=30 back="L2UI_CT1.Button_DF_Down" fore="L2UI_CT1.Button_DF">
                        <br>
                        <img src="L2UI_CH3.herotower_deco" width=256 height=32>
                    </center>
                </body>
            </html>
            """;
        
        NpcHtmlMessage npcHtmlMessage = new NpcHtmlMessage(0);
        npcHtmlMessage.setHtml(html.replace("%playerName%", player.getName()));
        player.sendPacket(npcHtmlMessage);
    }
    
    public void handleBypass(Player player, String bypass) {
        final StringTokenizer st = new StringTokenizer(bypass, " ");
        
        if (!st.hasMoreTokens()) {
            showMainMenu(player);
            return;
        }
        
        final String action = st.nextToken();
        
        if (action.startsWith("BuffEngine_Dispel")) {
            String[] parts = action.split("=");
            if (parts.length == 2) {
                try {
                    int skillId = Integer.parseInt(parts[1]);
                    player.stopSkillEffects(skillId);
                } catch (NumberFormatException e) {
                    LOGGER.warn("[" + getName() + "] Bypass BuffEngine_Dispel com skillId inválido: " + parts[1]);
                }
            }
            return;
        }
        
        switch (action) {
            case "GkGo":
                if (st.hasMoreTokens()) {
                    handleTeleportRequest(player, st.nextToken());
                }
                break;
            case "Shop":
                if (st.hasMoreTokens()) {
                    openAllowedMultisell(player, st.nextToken());
                }
                break;
            case "BossStatus":
                IVoicedCommandHandler command = VoicedCommandHandler.getInstance().getHandler("raid");
                if (command != null) {
                    command.useVoicedCommand("raid", player, "");
                }
                break;
            default:
                showMainMenu(player);
                break;
        }
    }
    
    private void handleAutoShot(Player player, String command) {
        final StringTokenizer st = new StringTokenizer(command, " ");
        st.nextToken(); // Pula "autoshot"

        if (st.hasMoreTokens()) {
            try {
                int shotId = Integer.parseInt(st.nextToken());
                // Lógica de toggle: inverte o estado atual.
                boolean currentlyActive = player.getAutoSoulShot().contains(Integer.valueOf(shotId));
                setAutoShotState(player, shotId, !currentlyActive);
            } catch (NumberFormatException e) {
                LOGGER.warn("[" + getName() + "] Invalid shotId in autoshot command: " + command);
            }
        }
    }

    private void setAutoShotState(Player player, int shotId, boolean enable) {
        if (player.isInStoreMode() || player.isDead()) {
            return;
        }

        final ItemInstance item = player.getInventory().getItemByItemId(shotId);
        // Verifica se o item existe e se é um item de shot válido.
        if (item == null || !item.getItem().isShot()) {
            return;
        }

        final Integer shotIdObj = Integer.valueOf(shotId);
        boolean isActive = player.getAutoSoulShot().contains(shotIdObj);

        if (enable && !isActive) {
            // Ativando o Auto-Shot
            player.addAutoSoulShot(shotIdObj);
            player.sendPacket(new ExAutoSoulShot(shotId, 0));
            player.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.AVOIDED_S1_ATTACK).addItemName(item));
            useShot(player, item);
        } else if (!enable && isActive) {
            // Desativando o Auto-Shot
            player.removeAutoSoulShot(shotIdObj);
            player.sendPacket(new ExAutoSoulShot(shotId, 0));
            player.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.CANNOT_USE_SOULSHOTS).addItemName(item));
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
        
        boolean allowed = false;
        for (int id : _allowedMultisells) {
            if (id == multisellId) {
                allowed = true;
                break;
            }
        }
        
        if (allowed) {
            MultisellData.getInstance().separateAndSend(String.valueOf(multisellId), player, null, false);
        } else {
            LOGGER.warn("Player " + player.getName() + " tentou abrir a multisell não permitida: " + multisellId);
            player.sendMessage("Este serviço não está disponível.");
        }
    }
    
    public void handleTeleportRequest(Player player, String command) {
        final String teleportId = command.replaceAll("[^0-9]", "");
        final TeleportLocation teleLocation = TeleportLocationData.getInstance().getTeleportLocation(teleportId);
        
        if (teleLocation == null) {
            LOGGER.warn("[" + getName() + "] Teleport ID '" + teleportId + "' não encontrado.");
            player.sendMessage("Local de teleporte inválido.");
            return;
        }
        
        if (!canTeleport(player, teleLocation)) {
            return;
        }
        
        int price = (player.getStatus().getLevel() >= 52) ? teleLocation.price() : 0;
        if (price > 0 && !player.destroyItemByItemId(57, price, true)) {
            player.sendPacket(SystemMessageId.YOU_NOT_ENOUGH_ADENA);
            return;
        }
        
        startTeleport(player, new Location(teleLocation.x(), teleLocation.y(), teleLocation.z()));
        
        player.sendPacket(ActionFailed.STATIC_PACKET);
    }
    
    private boolean canTeleport(Player player, TeleportLocation location) {
        final long teleportCooldown = _teleportCooldowns.getOrDefault(player.getObjectId(), 0L);
        if (teleportCooldown > System.currentTimeMillis()) {
            player.sendMessage("Você deve esperar para usar o teleporte novamente.");
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
        
        final L2Skill teleportSkill = SkillTable.getInstance().getInfo(_teleportSkillAnimationId, 1);
        if (teleportSkill == null) {
            LOGGER.warn("[" + getName() + "] Skill de teleporte com ID " + _teleportSkillAnimationId + " não encontrada.");
            player.sendMessage("Erro interno no teleporte.");
            return;
        }
        
        player.getAI().tryToCast(player, teleportSkill, false, false, 0);
        player.broadcastPacket(new MagicSkillUse(player, player, 2013, 1, _teleportCastTime, 0));
        player.sendPacket(new SetupGauge(GaugeColor.BLUE, _teleportCastTime));
        player.sendMessage("Teleporte iniciado. Aguarde por " + _teleportCastTime / 1000 + " segundos.");
        
        CompletableFuture.delayedExecutor(_teleportCastTime, TimeUnit.MILLISECONDS).execute(() -> {
            if (!player.getCast().isCastingNow()) {
                player.sendPacket(new CreatureSay(0, SayType.TELL, "Interface", "Seu teleporte foi cancelado."));
                return;
            }
            player.getCast().stop();
            if (player.isDead() || !player.isOnline()) {
                return;
            }
            player.teleToLocation(location);
        });
    }
    
    private static final class TeleportLocationData implements IXmlReader {
        private final Map<String, TeleportLocation> _teleports = new ConcurrentHashMap<>();
        
        @Override
        public void load() {
            _teleports.clear();
            
            parseDataFile("data/custom/mods/teleportLocationsInterface.xml");
        }
        
        @Override
        public void parseDocument(Document doc, Path path) {
            forEach(doc, "list", listNode -> forEach(listNode, "teleport", teleportNode -> {
                final StatSet set = new StatSet();
                final NamedNodeMap attrs = teleportNode.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    set.set(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
                }
                try {
                    final TeleportLocation loc = new TeleportLocation(set);
                    _teleports.put(loc.id(), loc);
                } catch (Exception e) {
                    LOGGER.warn(Level.WARNING, "Erro ao carregar teleport location do XML: " + set.getString("id", "UNKNOWN"), e);
                }
            }));
            LOGGER.info("TeleportLocationData: Loaded " + _teleports.size() + " interface teleport locations.");
        }
        
        public TeleportLocation getTeleportLocation(String id) {
            return _teleports.get(id);
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
