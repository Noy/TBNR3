package net.tbnr.dev;

import net.cogzmc.core.Core;
import net.cogzmc.core.network.NetworkServer;

import java.util.List;

public class ServerHelper {
    public static NetworkServer getLobbyServer(boolean vip) {
        List<NetworkServer> serversMatchingRegex = Core.getNetworkManager().getServersMatchingRegex("^" + (vip ? "vip" : "") + "lobby[0-9](1,4}$");
        if (serversMatchingRegex.size() == 0) return null;
        return serversMatchingRegex.get(Core.getRandom().nextInt(serversMatchingRegex.size()));
    }

    public static NetworkServer getServer(Game game) {
        String gameName = game.name().toLowerCase().replaceAll("_", "");
        List<NetworkServer> serversMatchingRegex = Core.getNetworkManager().getServersMatchingRegex("^" + gameName + "[0-9]{1,4}$");
        if (serversMatchingRegex.size() == 0) return null;
        return serversMatchingRegex.get(Core.getRandom().nextInt(serversMatchingRegex.size()));
    }
}