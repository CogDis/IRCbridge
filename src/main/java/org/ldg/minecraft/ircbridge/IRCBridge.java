package org.ldg.minecraft.ircbridge;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.permissions.*;
import org.bukkit.util.config.Configuration;

import com.platymuus.bukkit.permissions.Group;
import com.platymuus.bukkit.permissions.PermissionsPlugin;
import com.platymuus.bukkit.permissions.data.DataAccessException;

import com.hackhalo2.name.Special;

import org.jibble.pircbot.*;

public class IRCBridge extends JavaPlugin {
    Logger log = Logger.getLogger("Minecraft");

    String console_id;
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

    public PermissionsPlugin perms;
    HashMap<String,ChatColor> group_colors;
    ChatColor console_color;
    HashMap<String,ChatColor> irc_colors;

    private Bridge bridge;
    private String name;
    private long last_complaint_time;

    public void onDisable() {
        bridge.quitAll();
        bridge = null;
        perms = null;
        special = null;
    }

    public void onEnable() {
        last_complaint_time = 0;

        PluginManager pm = getServer().getPluginManager();
        perms = (PermissionsPlugin) pm.getPlugin("PermissionsBukkit");
        if (perms == null) {
            log.info("IRCBridge: PermissionsBukkit not found!");
            log.info("IRCBridge: Group-based colors will not be available.");
        }

        special = (Special) pm.getPlugin("Special");
        if (special == null) {
            log.info("IRCBridge: Special not found!");
            log.info("IRCBridge: Special integration will not be available.");
        }

        configure();

        PluginDescriptionFile pdfFile = this.getDescription();
        name = pdfFile.getName() + " " + pdfFile.getVersion();

        bridge = new Bridge(this);

        pm.registerEvent(Event.Type.PLAYER_JOIN, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_QUIT, bridge,
                         Event.Priority.Highest, this);
        pm.registerEvent(Event.Type.PLAYER_CHAT, bridge,
                         Event.Priority.Highest, this);

        bridge.connectAll(getServer().getOnlinePlayers());

        log.info(pdfFile.getName() + " " + pdfFile.getVersion() + " enabled." );
    }

    public ChatColor getColor(Configuration config, String node) {
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
        Configuration config = getConfiguration();
        config.load();

        // |Console will be appended to this.
        console_id = config.getString("console.id", "The");

        // Channel setup.
        console_channel = config.getString("channels.console", "#console");
        default_channel = config.getString("channels.default", "#minecraft");
        autojoin_channels = config.getStringList("channels.autojoin", null);
        big_channels = new HashSet<String>(config.getStringList("channels.big",
                                                                null));

        permission_channels = new HashMap<String,String>();
        List<String> permissions = config.getKeys("channels.permissions");
        if (permissions == null) {
            config.setProperty("channels.permissions.badass", "#badass");
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

        official_channels = new HashSet<String>(
                                      config.getStringList("channels.official",
                                                           default_official));

        group_colors = new HashMap<String,ChatColor>();
        if (perms != null) {
            List<Group> groups = new Vector<Group>();
            try {
                groups = perms.getAllGroups();
            } catch (DataAccessException e) {
            }

            for (Group group : groups) {
                ChatColor color = getColor(config,
                                           "color.group." + group.getName());
                group_colors.put(group.getName(), color);
            }
        }

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

        config.save();
    }

    public ChatColor colorOf(String name, boolean officialChannel) {
        if (name.startsWith("#")) {
            return ChatColor.GREEN;
        }

        // IRC prefixes
        String prefix = "none";
        if (!Character.isLetterOrDigit(name.charAt(0))) {
            prefix = name.substring(0,1);
            name = name.substring(1);
        }

        if (name.endsWith("|MC")) {
            if (perms == null) {
                return ChatColor.WHITE;
            }

            // Minecraft name.
            String username = name.substring(0, name.length() - 3);

            ChatColor color = null;
            try {
                for (Group group : perms.getGroups(username)) {
                    color = group_colors.get(group.getName());
                    if (color != null) {
                        break;
                    }
                }
            } catch (DataAccessException e) {
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

    public void complain(String message, Exception problem) {
        complain(message, problem, false);
    }

    public void complain(String message, Exception problem, boolean always) {
        if (!always) {
            long time = System.currentTimeMillis();
            if (time > last_complaint_time + 60 * 1000) {
                last_complaint_time = time;
            } else {
                return;
            }
        }

        log.severe(name + " " + message + ":");

        StringWriter traceback = new StringWriter();
        problem.printStackTrace(new PrintWriter(traceback));
        log.severe(traceback.toString());
    }

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
                                    + "Channel names must start with #");
            }

            for (Map.Entry<String,String> restricted
                                            : permission_channels.entrySet()) {
                if (restricted.getValue().equalsIgnoreCase(args[0])) {
                    if (!sender.hasPermission("ircbridge."
                                              + restricted.getKey())) {
                        connection.tellUser(ChatColor.RED
                                            + "Cannot join channel "
                                            + "(Invite only)");
                        return true;
                    } else {
                        break;
                    }
                }
            }

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
                                    + "Channel names must start with #");
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
                target = connection.revertName(args[0]);
            }

            connection.speaking_to = target;
            connection.tellUser(ChatColor.YELLOW
                                + "Your messages will now go to "
                                + connection.convertName(target, false)
                                + ChatColor.YELLOW + ".");
        } else if (cmd.equalsIgnoreCase("to")) {
            if (args.length < 2) {
                return false;
            }

            String message = "";
            for (int i=1; i<args.length; i++) {
                message += args[i] + " ";
            }

            if (connection != null) {
                String target = connection.revertName(args[0]);
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
                                   + "  (Try /who.)");
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
            if (args.length > 1) {
                return false;
            }

            if (args.length == 1) {
                try {
                    connection.who_page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            if (connection.speaking_to.startsWith("#")) {
                connection.sendRawLineViaQueue("NAMES "
                                               + connection.speaking_to);
            } else {
                connection.tellUser(ChatColor.RED + "You are talking to a "
                                    + "user, not a channel.");
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
                                        + " user, not a channel.");
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
                                    + "Channel names must start with #");
            }
        } else if (cmd.equalsIgnoreCase("reconnect")) {
            if (connection != null) {
                connection.tellUser(ChatColor.RED
                                    + "You're already connected!");
            } else {
                bridge.connectPlayer(player);
            }
        } else {
            return false;
        }

        return true;
    }

    private class Heartbeat implements Runnable {
        private IRCBridge plugin;

        public Heartbeat(IRCBridge plugin) {
            this.plugin = plugin;
        }

        public void run() {
        }
    }

    private class Bridge extends PlayerListener {
        private IRCBridge plugin;
        private HashMap<String,IRCConnection>
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

        public void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            log.info("IRCBridge: Connecting " + player.getName() + " to IRC.");
            connections.put(player.getName(),
                            new IRCConnection(plugin, player));
            event.setJoinMessage(null);
        }

        public void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();

            IRCConnection connection = connections.get(player.getName());
            if (connection != null) {
                connection.partAndQuit("Left Minecraft.");
                event.setQuitMessage(null);
            }
        }

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

        private Player player = null;

        public IRCConnection(IRCBridge plugin, CommandSender who) {
            this.plugin = plugin;
            if (who instanceof Player) {
                player = (Player) who;
            }

            Thread connection_thread = new Thread(this);
            connection_thread.start();
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
                nick = my_name + "|MC";
            }

            // Set the nickname.
            setName(nick);
            setLogin(name);

            // Pass in the user's info via WEBIRC.
            setPreNick("WEBIRC " + plugin.webirc_pass + " IRCBridge " + host
                       + " " + ip);

            // Speak to the default channel.
            speaking_to = plugin.default_channel;

            // Connect to the server.
            try {
                connect(plugin.server_address, plugin.server_port,
                        plugin.server_pass);
                tellUser(ChatColor.GREEN + "Connected to IRC.");
            } catch (IOException e) {
                tellUser(ChatColor.RED + "Unable to connect to IRC.");
                plugin.complain("unable to connect to IRC", e);
            } catch (IrcException e) {
                tellUser(ChatColor.RED + "Unable to connect to IRC.");
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

            // Join permission-based channels.
            for (String permission : plugin.permission_channels.keySet()) {
                if (player == null
                    || player.hasPermission("ircbridge." + permission)) {
                    joinChannel(plugin.permission_channels.get(permission));
                }
            }
        }

        protected void partAndQuit(String reason) {
            for (String channel : getChannels()) {
                partChannel(channel, reason);
            }

            quitServer(reason);
        }

        protected void onDisconnect() {
            tellUser(ChatColor.GRAY + "Connection to IRC lost.");
            tellUser(ChatColor.GRAY
                     + "Your default message target has been reset.");
        }

        public boolean isOfficial(String channel) {
            return plugin.official_channels.contains(channel);
        }

        protected void onUserList(String channel, User[] users) {
            String user_list = "";
            String sep = "";
            String page = "";

            boolean officialChannel = isOfficial(channel);

            HashMap<String,String> formats = new HashMap<String,String>();
            for (User user : users) {
                String name = user.getPrefix() + user.getNick();
                formats.put(convertNameWithoutColor(name),
                            convertName(name, officialChannel));
            }

            Vector<String> user_names = new Vector<String>(formats.keySet());
            Collections.sort(user_names);

            int count = user_names.size();
            if (count < 20) {
                for (String user : user_names) {
                    user_list += sep + formats.get(user);
                    sep = ChatColor.WHITE + ", ";
                }
            } else {
                int pages = count % 20;
                who_page = Math.max(0, Math.min(pages - 1, who_page - 1));
                page = " (page " + (who_page + 1) + "/" + pages + ")";

                for (int i=0; i<20; i++) {
                    int user_id = i + (who_page * 20);
                    if (user_id >= count) {
                        break;
                    }

                    String user = user_names.get(user_id);
                    user_list += sep + formats.get(user);
                    sep = ChatColor.WHITE + ", ";
                }
            }

            tellUser(formatChannel(channel) + ChatColor.WHITE + "Users" + page
                     + ": " + user_list);
        }

        protected void onTopic(String channel, String topic, String setBy,
                               long date, boolean changed) {
            tellUser(formatChannel(channel) + ChatColor.GREEN + topic);
        }

        protected void onJoin(String channel, String sender, String login,
                              String Hostname) {
            if (!plugin.big_channels.contains(channel)) {
                tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sender) + " joined the channel.");
            }
        }

        protected void onQuit(String sourceNick, String sourceLogin,
                              String sourceHostname, String reason) {
            if (   !sourceNick.toUpperCase().endsWith("|MC")
                && !sourceNick.toUpperCase().endsWith("|CONSOLE")) {
                tellUser(ChatColor.YELLOW + convertNameWithoutColor(sourceNick)
                         + " left IRC.");
            }
        }

        protected void onPart(String channel, String sender, String login,
                              String Hostname) {
            if (!plugin.big_channels.contains(channel)) {
                tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sender) + " left the channel.");
            }
        }

        protected void onKick(String channel, String kickerNick,
                              String kickerLogin, String kickerHostname,
                              String recipientNick, String reason) {
            tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(recipientNick) + " was kicked by "
                     + convertNameWithoutColor(kickerNick) + ": " + reason);
        }

        protected void onMode(String channel, String sourceNick,
                              String sourceLogin, String sourceHostname,
                              String mode) {
            tellUser(formatChannel(channel) + ChatColor.YELLOW +
                     convertNameWithoutColor(sourceNick) + " set mode " + mode);
        }

        protected void onNickChange(String oldNick, String login,
                                    String hostname, String newNick) {
            tellUser(ChatColor.YELLOW + convertNameWithoutColor(oldNick)
                     + " is now known as " + convertNameWithoutColor(newNick));
        }

        protected void onNotice(String sourceNick, String sourceLogin,
                                String sourceHostname, String target,
                                String notice) {
            if (sourceNick.toUpperCase().endsWith("SERV")) {
                tellUser(ChatColor.GOLD + convertNameWithoutColor(sourceNick)
                         + ": " + notice);
            }
        }

        protected void onServerResponse(int code, String response) {
            if (code >= 400 && code < 500) {
                String message = response.substring(response.indexOf(":") + 1);
                tellUser(ChatColor.RED + message);
            }
        }

        protected void onMessage(String channel, String sender, String login,
                                 String hostname, String message) {
            heard(sender, channel, message);
        }

        protected void onPrivateMessage(String sender, String login,
                                        String hostname, String message) {
            heard(sender, my_name, message);
        }

        protected void onAction(String sender, String login, String hostname,
                                String target, String action) {
            heard(sender, target, "/me " + action);
        }

        public void say(String message) {
            say(message, speaking_to);
        }

        public void say(String message, String target) {
            sendMessage(target, message);
            heard(getName(), target, message);
        }

        public String revertName(String name) {
            name = ChatColor.stripColor(name);
            if (name.startsWith("#")) {
                return name;
            } else if (name.equalsIgnoreCase("Console")) {
                return plugin.console_channel;
            } else if (name.toUpperCase().endsWith("|IRC")) {
                return name.substring(0, name.length()-4);
            } else {
                return name + "|MC";
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
            if (!Character.isLetterOrDigit(name.charAt(0))) {
                name = name.substring(1);
            }

            if (name.startsWith("#")) {
                return name;
            } else if (name.toUpperCase().endsWith("|MC")) {
                return name.substring(0, name.length()-3);
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

        public void heard(String who, String where, String what) {
            who = convertName(getPrefix(who,where) + who, isOfficial(where));

            String intro;
            if (where == null) {
                intro = who + "->Console: ";
            } else if (!where.startsWith("#")) {
                if (ChatColor.stripColor(who).equalsIgnoreCase(my_name)) {
                    intro = ChatColor.GREEN + "To " + convertName(where, false)
                            + ChatColor.GREEN + ": " + ChatColor.WHITE;
                } else {
                    intro = ChatColor.GREEN + "From " + who + ChatColor.GREEN
                            + ": " + ChatColor.WHITE;
                }
            } else {
                where = formatChannel(where) + ChatColor.WHITE;

                if (what.startsWith("/me ")) {
                    what = what.substring(4);
                    intro = where + "* " + who + ChatColor.WHITE + " ";
                } else {
                    intro = where + "<" + who + ChatColor.WHITE + "> ";
                }
            }

            tellUser(intro + what);
        }

        public void tellUser(String message) {
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
}
