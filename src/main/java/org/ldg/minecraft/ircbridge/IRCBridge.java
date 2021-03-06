package org.ldg.minecraft.ircbridge;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;
import org.ldg.minecraft.EnhancedPlugin;

import ru.tehkode.permissions.PermissionManager;
import ru.tehkode.permissions.PermissionUser;
import ru.tehkode.permissions.bukkit.PermissionsEx;

import com.hackhalo2.name.Special;


public class IRCBridge extends EnhancedPlugin {
    Logger message_log = Logger.getLogger("ircbridge.pms");
    FileHandler message_file = null;
    boolean log_pms;

    final int WHO_PAGE_SIZE=40;

    String console_id;
    String console_tag;
    String default_channel;
    String console_channel;

    // Server connection info.
    String server_address;
    int server_port;
    String server_pass;
    String webirc_pass;

    List<String> autojoin_channels;
    HashSet<String> big_channels;
    HashSet<String> official_channels;
    HashMap<String,String> permission_channels;

    public Special special;

    public PermissionsEx permsPlugin;
    public PermissionManager perms;
    //HashMap<String,ChatColor> group_colors;
    ChatColor console_color;
    HashMap<String,ChatColor> irc_colors;

    private Bridge bridge;
    private String name;
    private long startup_time;
    private boolean shutting_down = false;

    public static final int ALL = 0;
    public static final int MINECRAFT = 1;
    public static final int IRC = 2;
    
    private File dataFile;

    public boolean beingQuiet() {
        if (shutting_down || startup_time + 5000 > System.currentTimeMillis()) {
            return true;
        }

        return false;
    }
    
    @Override
    public void onLoad() {
	this.dataFile = new File(this.getDataFolder(), "config.yml");
    }
    
    @Override
    public void onDisable() {
        shutting_down = true;
        bridge.quitAll();
        bridge = null;
        perms = null;
        special = null;

        if (message_file != null) {
            message_log.removeHandler(message_file);
            message_log.setUseParentHandlers(true);
            message_file = null;
        }

        super.onDisable();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        shutting_down = false;
        startup_time = System.currentTimeMillis();

        try {
            message_file = new FileHandler("pms.log", true);
            message_file.setFormatter(new TinyFormatter());
            message_log.addHandler(message_file);
            message_log.setUseParentHandlers(false);
        } catch (Exception e) {
            message_file = null;
            complain("unable to open message log file", e);
        }

        PluginManager pm = getServer().getPluginManager();
        permsPlugin = (PermissionsEx) pm.getPlugin("PermissionsEx");
        if (permsPlugin == null) {
            log.info("IRCBridge: PermissionsEx not found!");
            log.info("IRCBridge: Group-based colors will not be available.");
        }
        perms = PermissionsEx.getPermissionManager();

        special = (Special) pm.getPlugin("Special");
        if (special == null) {
            log.info("IRCBridge: Special not found!");
            log.info("IRCBridge: Special integration will not be available.");
        }

        configure();

        bridge = new Bridge(this);

        /*pm.registerEvent(Event.Type.PLAYER_JOIN, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_QUIT, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_KICK, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_CHAT, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.ENTITY_DEATH, new DeathNotify(this),
                Event.Priority.Monitor, this);*/
        pm.registerEvents(bridge, this);
        pm.registerEvents(new DeathNotify(this), this);
        bridge.connectAll(getServer().getOnlinePlayers());
    }

    public ChatColor getColor(YamlConfiguration config, String node) {
        ChatColor color = ChatColor.WHITE;

        String color_name = config.getString(node, "white");
        color_name = color_name.toUpperCase().replace(" ", "_");
        try {
            color = ChatColor.valueOf(color_name);
        } catch (Exception e) {
            if (!color_name.equals("NONE")) {
                complain("bad color for " + node, e, true);
            }
        }

        return color;
    }


    public void configure() {
	YamlConfiguration config = new YamlConfiguration();
	try {
	    config.load(dataFile);
	} catch (Exception e) {
	    Bukkit.getLogger().warning("[PChat] Error loading IRCBridge Configuration!");
	}

        log_pms = config.getBoolean("log.pms", false);

        // |Console will be appended to this.
        console_id = config.getString("console.id", "The");
        console_tag = config.getString("console.tag", "NA");

        // Channel setup.
        console_channel = config.getString("channels.console", "#console");
        default_channel = config.getString("channels.default", "#minecraft");
        autojoin_channels = config.getStringList("channels.autojoin");
        big_channels = new HashSet<String>(config.getStringList("channels.big"));

        permission_channels = new HashMap<String,String>();
        Set<String> channelPermissions = config.getConfigurationSection("channels.permissions").getKeys(false);
	List<String> permissions;
	if (channelPermissions != null) {
	    permissions = Arrays.asList(channelPermissions.toArray(new String[0]));
	    if(permissions == null) {
		config.set("channels.permissions.badass", "#badass");
		permissions = new Vector<String>();
		permissions.add("badass");
	    }
	} else {
	    config.set("channels.permissions.badass", "#badass");
	    permissions = new Vector<String>();
	    permissions.add("badass");
	}

        // Record and register the permissions for permission-based channels.
        PluginManager pm = getServer().getPluginManager();
        for (String permission : permissions) {
            String channel = config.getString("channels.permissions."
                                              + permission);
            permission_channels.put(permission, channel);
            pm.addPermission(new Permission("ircbridge." + permission,
                                            "Allows access to " + channel,
                                            PermissionDefault.OP));
        }

        // The console channel, default channel, autojoin channels, and
        // permission-based channels are considered official by default, and
        // apply IRC-based nick colors.
        Vector<String> default_official = new Vector<String>();
        default_official.add(console_channel);
        default_official.add(default_channel);
        for (String channel : autojoin_channels) {
            default_official.add(channel);
        }
        for (String channel : permission_channels.values()) {
            default_official.add(channel);
        }

        official_channels = new HashSet<String>(config.getStringList("channels.official"));

        irc_colors = new HashMap<String,ChatColor>();
        String[] prefixes = new String[] {"~","&","@","%","+","none"};

        for (String prefix : prefixes) {
            ChatColor color = getColor(config, "color.irc.prefix." + prefix);
            irc_colors.put(prefix, color);
        }

        console_color = getColor(config, "color.console");

        // Server connection info.
        server_address = config.getString("server.address", "localhost");
        server_port = config.getInt("server.port", 6667);
        server_pass = config.getString("server.password", "");
        webirc_pass = config.getString("server.webirc_password", "");

        try {
	    config.save(dataFile);
	} catch (Exception e) {
	    Bukkit.getLogger().warning("[PChat] Error saving IRCBridge Configuration! Aborting save!");
	    Bukkit.getLogger().info(e.getCause().getMessage());
	}
    }

    public void logMessage(String message) {
        if (log_pms) {
            message_log.info(message);
        }
    }

    public ChatColor colorOf(String name, boolean officialChannel) {
        if (name.startsWith("#")) {
            return ChatColor.GREEN;
        }

        // IRC prefixes
        String prefix = "none";
        if (!Character.isLetter(name.charAt(0))) {
            prefix = name.substring(0,1);
            name = name.substring(1);
        }

        if (prefix.equalsIgnoreCase("_")) {
            prefix = "none";
        } else if (name.charAt(0) == '_' && name.endsWith("|" + console_tag + "|MC")) {
            name = name.substring(1);
        }

        if (name.endsWith("|" + console_tag + "|MC")) {
            if (perms == null) {
                return ChatColor.WHITE;
            }

            // Minecraft name.
            String username = name.substring(0, name.length() - (4 + console_tag.length()));

            ChatColor color = null;
            try {
                    PermissionUser user = perms.getUser(username);
                    color = ChatColor.valueOf(user.getOption("ircbridge.color"));

            } catch (Exception e) {
                complain("unable to get group information for " + username, e);
            }

            if (color == null) {
                return ChatColor.WHITE;
            } else {
                return color;
            }
        } else if (name.endsWith("|Console")) {
            return console_color;
        } else {
            // In non-official channels, everyone is white, to avoid confusion.
            if (!officialChannel) {
                return ChatColor.WHITE;
            }

            ChatColor prefix_color = irc_colors.get(prefix);
            if (prefix_color != null) {
                return prefix_color;
            } else {
                return ChatColor.WHITE;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String commandLabel, String[] args) {
        Player player = null;
        String name = "*CONSOLE*";
        String cmd = command.getName();

        if (sender instanceof Player) {
            player = (Player) sender;
            name = player.getName();
        }

        IRCConnection connection = bridge.connections.get(name);

        if (connection == null || !connection.isConnected()) {
            if (cmd.equalsIgnoreCase("say") || cmd.equalsIgnoreCase("to") ||
                cmd.equalsIgnoreCase("reconnect")) {
                connection = null;
            } else if (!cmd.equalsIgnoreCase("list")) {
                sender.sendMessage("You're not connected to IRC.  "
                                   + "(try /reconnect)");
                return true;
            }
        }

        if (cmd.equalsIgnoreCase("join")) {
            if (args.length < 1 || args.length > 2) {
                return false;
            }

            if (!args[0].startsWith("#")) {
                connection.tellUser(ChatColor.RED
                                    + "Channel names must start with #", true);
                return true;
            }

            for (Map.Entry<String,String> restricted
                                            : permission_channels.entrySet()) {
                if (restricted.getValue().equalsIgnoreCase(args[0])) {
                    if (!sender.hasPermission("ircbridge."
                                              + restricted.getKey())) {
                        connection.tellUser(ChatColor.RED
                                            + "Cannot join channel "
                                            + "(Invite only)", true);
                        return true;
                    } else {
                        break;
                    }
                }
            }

            // Get a full user list on join.
            connection.who_target = args[0];
            connection.who_page = 1;
            connection.who_mode = ALL;

            if (args.length == 1) {
                connection.joinChannel(args[0]);
            } else {
                connection.joinChannel(args[0], args[1]);
            }
        } else if (cmd.equalsIgnoreCase("part")) {
            if (args.length != 1) {
                return false;
            }

            if (!args[0].startsWith("#")) {
                connection.tellUser(ChatColor.RED
                                    + "Channel names must start with #", true);
                return true;
            }

            connection.partChannel(args[0]);
        } else if (cmd.equalsIgnoreCase("switch")) {
            if (args.length > 1) {
                return false;
            }

            String target;
            if (args.length == 0) {
                target = default_channel;
            } else {
                target = connection.matchUser(args[0]);
            }

            connection.speaking_to = target;
            connection.tellUser(ChatColor.YELLOW
                                + "Your messages will now go to "
                                + connection.convertName(target, false)
                                + ChatColor.YELLOW + ".", true);
        } else if (cmd.equalsIgnoreCase("to")) {
            if (args.length < 2) {
                return false;
            }

            String message = "";
            for (int i=1; i<args.length; i++) {
                message += args[i] + " ";
            }

            if (connection != null) {
                String target = connection.matchUser(args[0]);
                connection.say(message.trim(), target);
            } else {
                List<Player> players = getServer().matchPlayer(args[0]);
                if (players.size() == 0) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                } else if (players.size() > 1) {
                    sender.sendMessage(ChatColor.RED + "Be more specific.");
                    return true;
                } else {
                    Player target = players.get(0);
                    sender.sendMessage(ChatColor.GREEN + "To "
                                       + target.getName() + ": "
                                       + ChatColor.WHITE + message.trim());
                    target.sendMessage(ChatColor.GREEN + "From " + name
                                       + ": " + ChatColor.WHITE
                                       + message.trim());
                    return true;
                }
            }
        } else if (cmd.equalsIgnoreCase("say")) {
            String message = "";
            for (int i=0; i<args.length; i++) {
                message += args[i] + " ";
            }

            if (connection != null) {
                connection.say(message.trim());
            } else {
                getServer().broadcastMessage("<" + name + "> " + message);
            }
        } else if (cmd.equalsIgnoreCase("me")) {
            String message = "";
            for (int i=0; i<args.length; i++) {
                message += args[i] + " ";
            }
            connection.say("/me " + message);
        } else if (cmd.equalsIgnoreCase("list")) {
            if (!sender.hasPermission("ircbridge.list")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission."
                                   + "  (Try /who, /irc, or /users.)");
                return true;
            }

            String message = "Connected players: ";
            String sep = "";
            for (Player online : getServer().getOnlinePlayers()) {
                message += sep + online.getName();
                sep = ", ";
            }
            sender.sendMessage(message);
        } else if (cmd.equalsIgnoreCase("who")) {
            if (args.length > 2) {
                return false;
            }

            connection.who_page = 0;
            connection.who_target = connection.speaking_to;
            if (args.length == 1) {
                if (args[0].startsWith("#")) {
                    // /<command> <#channel>
                    connection.who_target = args[0];
                } else {
                    // /<command> <page>
                    try {
                        connection.who_page = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            } else if (args.length == 2) {
                // /<command> <#channel> <page>
                if (!args[0].startsWith("#")) {
                    return false;
                }

                try {
                    connection.who_page = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    return false;
                }

                connection.who_target = args[0];
            }

            String alias = commandLabel.toLowerCase();
            if (alias.startsWith("irc")) {
                connection.who_mode = IRC;
            } else if (   alias.equals("who")
                       || alias.contains("players")) {
                connection.who_mode = MINECRAFT;
            } else { // /all, /whoall, /names, /users
                connection.who_mode = ALL;
            }

            if (connection.who_target.startsWith("#")) {
                connection.sendRawLineViaQueue("NAMES "
                                               + connection.who_target);
            } else {
                connection.tellUser(ChatColor.RED + "You are talking to a "
                                    + "user, not a channel.", true);
            }
        } else if (cmd.equalsIgnoreCase("mode")) {
            if (args.length == 0) {
                return false;
            }

            if (args[0].startsWith("#")
                || args[0].equalsIgnoreCase(connection.my_name)) {
                String mode = "";
                for (int i=1; i<args.length; i++) {
                    mode += args[i] + " ";
                }
                connection.setMode(args[0], mode.trim());
            } else {
                String mode = "";
                for (int i=0; i<args.length; i++) {
                    mode += args[i] + " ";
                }

                if (connection.speaking_to.startsWith("#")) {
                    connection.setMode(connection.speaking_to, mode.trim());
                } else {
                    connection.tellUser(ChatColor.RED + "You are talking to a"
                                        + " user, not a channel.", true);
                }
            }
        } else if (cmd.equalsIgnoreCase("irckick")) {
            if (args.length < 1 || args.length > 2) {
                return false;
            }

            String channel;
            String target;
            if (args.length == 2) {
                channel = args[0];
                target = args[1];
            } else {
                channel = connection.speaking_to;
                target = args[0];
            }

            if (connection.speaking_to.startsWith("#")) {
                connection.kick(channel, target);
            } else {
                connection.tellUser(ChatColor.RED
                                    + "Channel names must start with #", true);
            }
        } else if (cmd.equalsIgnoreCase("reconnect")) {
            if (connection != null) {
                connection.tellUser(ChatColor.RED
                                    + "You're already connected!", true);
            } else {
                bridge.connectPlayer(player);
            }
        } else {
            return false;
        }

        return true;
    }
    private class DeathNotify implements Listener{
    	private final IRCBridge plugin;
    	public DeathNotify(IRCBridge p)
    	{
    		plugin = p;
    	}
    	
    	@EventHandler
    	public void onEntityDeath(EntityDeathEvent event)
    	{
    		
    		if(event instanceof PlayerDeathEvent)
    		{
    			PlayerDeathEvent pevent = (PlayerDeathEvent) event;
    			IRCConnection connection = plugin.bridge.connections.get("*CONSOLE*");
    			if(connection != null && connection.isConnected())
    			{
    				String dMessage = pevent.getDeathMessage();
    				dMessage = dMessage.replaceAll("\\u00A7.", "");
        			connection.sendMessage(default_channel, "Event: " + dMessage);
    			}
    		}
    	}
    }

    private class Bridge implements Listener {
        private final IRCBridge plugin;
        private final HashMap<String,IRCConnection>
            connections = new HashMap<String,IRCConnection>();

        public Bridge(IRCBridge plugin) {
            this.plugin = plugin;
        }

        public void connectAll(Player[] players) {
            connectPlayer(null);
            for (Player player : players) {
                connectPlayer(player);
            }
        }

        public void connectPlayer(Player player) {
            String name = "*CONSOLE*";
            if (player != null) {
                name = player.getName();
            }

            log.info("IRCBridge: Reconnecting " + name + " to IRC.");
            connections.put(name, new IRCConnection(plugin, player));
        }

        public void quitAll() {
            for (IRCConnection connection : connections.values()) {
                connection.quitServer("IRCBridge closing.");
            }
            connections.clear();
        }
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {

            Player player = event.getPlayer();
            String name = player.getName();
            if (connections.containsKey(name)) {
                IRCConnection connection = connections.get(name);
                if (connection.isConnected()) {
                    connection.partAndQuit("Re-establishing connection.");
                }
            }
            log.info("IRCBridge: Connecting " + name + " to IRC.");
            connections.put(name, new IRCConnection(plugin, player));
            event.setJoinMessage(null);
        }

        public boolean playerLeft(Player player) {
            IRCConnection connection = connections.get(player.getName());
            if (connection != null) {
                connection.partAndQuit("Left Minecraft.");
                return true;
            }
            return false;
        }
        @EventHandler
        public void onPlayerKick(PlayerKickEvent event) {
            if (playerLeft(event.getPlayer())) {
                event.setLeaveMessage(null);
            }
        }
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            if (playerLeft(event.getPlayer())) {
                event.setQuitMessage(null);
            }
        }
        @EventHandler
        public void onPlayerChat(PlayerChatEvent event) {
            String message = event.getMessage();
            Player player = event.getPlayer();
            IRCConnection connection = connections.get(player.getName());

            if (!message.startsWith("/")
                && connection != null && connection.isConnected()) {
                // We'll handle this ourselves.
                event.setCancelled(true);

                connection.say(message);
            }
        }
    }

    private class IRCConnection extends PircBot implements Runnable {
        public IRCBridge plugin;

        public String my_name = null;
        public String speaking_to;

        public int who_page = 1;
        public String who_target = null;
        public int who_mode = IRCBridge.MINECRAFT;

        private Player player = null;
        ArrayList<String> AllowedChannels;

        public IRCConnection(IRCBridge plugin, CommandSender who) {
            this.plugin = plugin;
            if (who instanceof Player) {
                player = (Player) who;
            }
            AllowedChannels = new ArrayList<String>();
            // Join permission-based channels.
            for (String permission : plugin.permission_channels.keySet()) {
                if (player == null
                    || player.hasPermission("ircbridge." + permission)) {
                	AllowedChannels.add(plugin.permission_channels.get(permission));
                    //joinChannel(plugin.permission_channels.get(permission));
                }
            }
            Thread connection_thread = new Thread(this);
            connection_thread.start();
            //run();
        }

        public void run() {
        	
            // User info for console.
            String host = "localhost";
            String ip = "127.0.0.1";
            String name = "Console";
            String nick = plugin.console_id + "|Console";

            // If we're not console, use the player's info.
            if (player != null) {
                my_name = player.getName();
                ip = player.getAddress().getAddress().getHostAddress();
                host = player.getAddress().getHostName();
                name = my_name;
                nick = my_name + "|" + console_tag + "|MC";
                if (!Character.isLetter(nick.charAt(0))) {
                    nick = "_" + nick;
                }
            }

            // Set the nickname.
            setName(nick);
            setLogin(name);

            // Pass in the user's info via WEBIRC.
            setPreNick("WEBIRC " + plugin.webirc_pass + " IRCBridge " + host
                       + " " + ip);

            // Speak to the default channel.
            speaking_to = plugin.default_channel;

            // Get an initial user list from the default channel.
            who_target = speaking_to;

            // Connect to the server.
            try {
                connect(plugin.server_address, plugin.server_port,
                        plugin.server_pass);
                tellUser(ChatColor.GREEN + "Connected to IRC.", true);
            } catch (IOException e) {
                tellUser(ChatColor.RED + "Unable to connect to IRC.", true);
                plugin.complain("unable to connect to IRC", e);
            } catch (IrcException e) {
                tellUser(ChatColor.RED + "Unable to connect to IRC.", true);
                plugin.complain("unable to connect to IRC", e);
            }

            if (player == null) {
                // Console doesn't need a hostmask.
                setMode(nick, "-x");

                // Join the console channel.
                joinChannel(plugin.console_channel);
            }

            // Join the default channel.
            joinChannel(plugin.default_channel);

            // Join autojoin channels.
            for (String channel : plugin.autojoin_channels) {
                joinChannel(channel);
            }
            
            //Join the allowed channels
            for (String channel : this.AllowedChannels)
            {
            	joinChannel(channel);
            }
            
        }

        protected void partAndQuit(String reason) {
            for (String channel : getChannels()) {
                partChannel(channel, reason);
            }

            quitServer(reason);
        }

        @Override
	protected void onDisconnect() {
            tellUser(ChatColor.GRAY + "Connection to IRC lost.", true);
            tellUser(ChatColor.GRAY
                     + "Your default message target has been reset.", true);
        }

        public boolean isOfficial(String channel) {
            return plugin.official_channels.contains(channel);
        }

        @Override
	protected void onUserList(String channel, User[] users) {
            if (!channel.equalsIgnoreCase(who_target)) {
                // Ignore user lists unless requested.
                return;
            }

            String user_list = "";
            String sep = "";
            String page = "";

            boolean officialChannel = isOfficial(channel);

            int ignored = 0;
            boolean ignore_irc = (who_mode == IRCBridge.MINECRAFT);
            boolean ignore_minecraft = (who_mode == IRCBridge.IRC);

            HashMap<String,String> formats = new HashMap<String,String>();
            for (User user : users) {
                String name = user.getPrefix() + user.getNick();
                boolean is_minecraft = name.toUpperCase().endsWith("|" + console_tag + "|MC");
                if (   (ignore_irc       && !is_minecraft)
                    || (ignore_minecraft &&  is_minecraft)) {
                    ignored++;
                    continue;
                }

                formats.put(convertNameWithoutColor(name),
                            convertName(name, officialChannel));
            }

            Vector<String> user_names = new Vector<String>(formats.keySet());
            Collections.sort(user_names);

            int count = user_names.size();
            int pages = (int) Math.ceil(count / (float) WHO_PAGE_SIZE);
            if (pages == 1) {
                for (String user : user_names) {
                    user_list += sep + formats.get(user);
                    sep = ChatColor.WHITE + ", ";
                }
            } else {
                who_page = Math.max(0, Math.min(pages - 1, who_page - 1));
                page = " (page " + (who_page + 1) + "/" + pages + ")";

                for (int i = 0; i < WHO_PAGE_SIZE; i++) {
                    int user_id = i + (who_page * WHO_PAGE_SIZE);
                    if (user_id >= count) {
                        break;
                    }

                    String user = user_names.get(user_id);
                    user_list += sep + formats.get(user);
                    sep = ChatColor.WHITE + ", ";
                }
            }

            if ((ignored > 0) && ((pages == 1) || (who_page == (pages-1)))) {
                String s = ignored > 1 ? "s" : "";
                String start = ChatColor.WHITE + (count > 1 ? "," : "");
                if (ignore_irc) {
                    user_list += start + " and " + ignored + " IRC user" + s
                                 + " (see /irc or /users).";
                } else { // ignore_minecraft
                    user_list += start + " and " + ignored + " Minecraft user"
                                 + s + " (see /who or /users).";
                }
            }

            tellUser(formatChannel(channel) + ChatColor.WHITE + "Users" + page
                     + ": " + user_list);
        }

        @Override
	protected void onTopic(String channel, String topic, String setBy,
                               long date, boolean changed) {
            if (topic.trim().equals("")) {
                // Hide empty topics.
                return;
            }
            tellUser(formatChannel(channel) + ChatColor.GREEN + topic, true);
        }

        @Override
	protected void onJoin(String channel, String sender, String login,
                              String Hostname) {
            if (!plugin.big_channels.contains(channel)) {
                tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sender) + " joined the channel.");
            }
        }

        @Override
	protected void onQuit(String sourceNick, String sourceLogin,
                              String sourceHostname, String reason) {
            if (   !sourceNick.toUpperCase().endsWith("|" + console_tag + "|MC")
                && !sourceNick.toUpperCase().endsWith("|CONSOLE")) {
                tellUser(ChatColor.YELLOW + convertNameWithoutColor(sourceNick)
                         + " left IRC.");
            }
        }

        @Override
	protected void onPart(String channel, String sender, String login,
                              String Hostname) {
            if (!plugin.big_channels.contains(channel)) {
                tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sender) + " left the channel.");
            }
        }

        @Override
	protected void onKick(String channel, String kickerNick,
                              String kickerLogin, String kickerHostname,
                              String recipientNick, String reason) {
            tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(recipientNick) + " was kicked by "
                     + convertNameWithoutColor(kickerNick) + ": " + reason, true);
        }

        @Override
	protected void onMode(String channel, String sourceNick,
                              String sourceLogin, String sourceHostname,
                              String mode) {
            if (isOfficial(channel) && sourceNick.equalsIgnoreCase("ChanServ")) {
                // Suppress ChanServ's ramblings in official channels.
                return;
            }
            tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sourceNick) + " set mode " + mode, true);
        }

        @Override
	protected void onNickChange(String oldNick, String login,
                                    String hostname, String newNick) {
            tellUser(ChatColor.YELLOW + convertNameWithoutColor(oldNick)
                     + " is now known as " + convertNameWithoutColor(newNick), true);
        }

        @Override
	protected void onNotice(String sourceNick, String sourceLogin,
                                String sourceHostname, String target,
                                String notice) {
            if (sourceNick.toUpperCase().endsWith("SERV")) {
                if (sourceNick.equalsIgnoreCase("NickServ")
                    && notice.endsWith(" is not a registered nickname.")) {
                    // Shaddup, NickServ.  Minecraft users don't need to
                    // register if the IRC server is set up properly.
                    return;
                }

                tellUser(ChatColor.GOLD + convertNameWithoutColor(sourceNick)
                         + ": " + notice, true);
            }
        }

        @Override
	protected void onServerResponse(int code, String response) {
            if (code >= 400 && code < 500) {
                String message = response.substring(response.indexOf(":") + 1);
                tellUser(ChatColor.RED + message, true);
            }
        }

        @Override
	protected void onMessage(String channel, String sender, String login,
                                 String hostname, String message) {
            heard(sender, channel, message);
        }

        @Override
	protected void onPrivateMessage(String sender, String login,
                                        String hostname, String message) {
            plugin.logMessage(sender + "->" + getName() + ": " + message);
            heard(sender, getName(), message);
        }

        @Override
	protected void onAction(String sender, String login, String hostname,
                                String target, String action) {
            heard(sender, target, "/me " + action);
            if (!target.startsWith("#")) {
                plugin.logMessage(sender + "->" + getName() + ":* " + action);
            }
        }

        public void say(String message) {
            say(message, speaking_to);
        }

        public void say(String message, String target) {
            sendMessage(target, message);
            heard(getName(), target, message);
            if (!target.startsWith("#")) {
                plugin.logMessage(getName() + "->" + target + ": " + message);
            }
        }

        public String revertName(String name) {
            name = ChatColor.stripColor(name);
            if (name.startsWith("#")) {
                return name;
            } else if (name.equalsIgnoreCase("Console")) {
                return plugin.console_channel;
            } else if (name.toUpperCase().endsWith("|IRC")) {
                return name.substring(0, name.length()-4);
            } else if (!Character.isLetter(name.charAt(0))) {
                return "_" + name + "|" + console_tag + "|MC";
            } else {
                return name + "|" + console_tag + "|MC";
            }
        }

        public String convertName(String name, boolean officialChannel) {
            if (special != null) {
                return plugin.colorOf(name, officialChannel)
                       + special.format(convertNameWithoutColor(name));
            } else {
                return plugin.colorOf(name, officialChannel)
                       +                convertNameWithoutColor(name) ;
            }
        }

        public String convertNameWithoutColor(String name) {
            if (name.startsWith("#")) {
                return name;
            } else if (!Character.isLetter(name.charAt(0))) {
                // _ is a valid first character for IRC users.
                // Minecraft users fix otherwise-broken usernams with it.
                if (   name.charAt(0) != '_'
                    || name.toUpperCase().endsWith("|" + console_tag + "|MC")) {
                    name = name.substring(1);
                }
            }

            if (name.toUpperCase().endsWith("|" + console_tag + "|MC")) {
                return name.substring(0, name.length()-(4+console_tag.length()));
            } else if (name.endsWith("|Console")) {
                return "Console";
            } else {
                return name + "|IRC";
            }
        }

        public String formatChannel(String channel) {
            if (channel.equalsIgnoreCase(plugin.default_channel)) {
                return "";
            } else {
                return ChatColor.GREEN + channel + " ";
            }
        }

        public String getPrefix(String nick, String channel) {
            for (User user : getUsers(channel)) {
                if (user.getNick().equalsIgnoreCase(nick)) {
                    return user.getPrefix();
                }
            }

            return "";
        }

        private void getMatches(HashMap<String,String> matches, String nick,
                                  String channel) {
            for (User user : getUsers(channel)) {
                String user_nick = user.getNick().toLowerCase();
                if (user_nick.endsWith("|console")) {
                    // Skip the console.
                    continue;
                }

                if (user_nick.startsWith("_") && user_nick.endsWith("|" + console_tag.toLowerCase() + "|mc")) {
                    user_nick = user_nick.substring(1);
                }

                if (user_nick.startsWith(nick)) {
                    matches.put(user_nick, user.getNick());
                }
            }
        }

        public String matchUser(String rawnick) {
            String reverted = revertName(rawnick);
            // If a full name/channel has been specified, use it.
            if (!reverted.endsWith("|" + console_tag + "|MC")) {
                return reverted;
            } else if (rawnick.toUpperCase().endsWith("|" + console_tag + "|MC")) {
                return rawnick;
            }

            String nick = rawnick.toLowerCase();
            HashMap<String,String> matches = new HashMap<String,String>();
            if (speaking_to.startsWith("#")) {
                // Match against users in the current channel.
                getMatches(matches, nick, speaking_to);

                if (matches.size() == 1) {
                    for (String match : matches.keySet()) {
                        // Silly, but an easy way to grab the only element.
                        return matches.get(match);
                    }
                } else if (matches.containsKey(nick + "|" + console_tag.toLowerCase() + "|mc")) {
                    return matches.get(nick + "|" + console_tag.toLowerCase() + "|mc");
                } else if (matches.containsKey(nick)) {
                    return matches.get(nick);
                }
            } else if (speaking_to.toLowerCase().startsWith(nick)) {
                return speaking_to;
            }

            for (String channel : getChannels()) {
                getMatches(matches, nick, channel);
            }

            if (matches.size() == 1) {
                for (String match : matches.keySet()) {
                    // Silly, but an easy way to grab the only element.
                    return matches.get(match);
                }
            } else if (matches.containsKey(nick + "|" + console_tag.toLowerCase() + "|mc")) {
                return matches.get(nick + "|" + console_tag.toLowerCase() + "|mc");
            } else if (matches.containsKey(nick)) {
                return matches.get(nick);
            }

            return reverted;
        }

        public void heard(String who, String where, String what) {
            String display_who = convertName(getPrefix(who,where) + who,
                                             isOfficial(where));
            if(display_who.endsWith("Console") && what.startsWith("Event:"))
            {
            	return;
            }
            String intro;
            if (where == null) {
                intro = who + "->Console:";
            } else if (!where.startsWith("#")) {
                if (who.equalsIgnoreCase(getName())) {
                    intro = ChatColor.GREEN + "To " + convertName(where, false)
                            + ChatColor.GREEN + ":" + ChatColor.LIGHT_PURPLE;
                } else {
                    intro = ChatColor.GREEN + "From " + display_who
                            + ChatColor.GREEN + ":" + ChatColor.LIGHT_PURPLE;
                }
            } else {
                ChatColor text_color = ChatColor.WHITE;

                if (plugin.permission_channels.containsValue(where)) {
                    text_color = ChatColor.GREEN;
                }

                where = formatChannel(where) + text_color;

                if (what.startsWith("/me ")) {
                    what = what.substring(4);
                    intro = where + "* " + display_who + text_color;
                } else {
                    intro = where + "" + display_who + text_color + ":";
                }
            }

            tellUser(intro + " " + what, true);
        }

        public void tellUser(String message) {
            tellUser(message, false);
        }

        public void tellUser(String message, boolean always) {
            if (!always && plugin.beingQuiet()) {
                // Non-critical messages get suppressed during reloads.
                return;
            }

            if (my_name != null) {
                Player player = plugin.getServer().getPlayer(my_name);
                if (player != null) {
                    player.sendMessage(message);
                }
            } else {
                log.info(ChatColor.stripColor(message));
            }
        }
    }

    private class TinyFormatter extends Formatter {
        private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        @Override
	public String format(LogRecord record) {
            return timestamp.format(record.getMillis()) + " "
                   + record.getMessage() + "\n";
        }
    }
}
