package io.github.cong;

import arc.util.Time;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import mindustry.Vars;
import mindustry.gen.PingCallPacket;

import java.util.HashMap;
import java.util.Map;

public class MindustryPlayerPingProvider implements PlayerPingProvider {

    Map<String, Integer> pingMap = new HashMap<>();

    MindustryPlayerPingProvider() {
        Vars.net.handleServer(PingCallPacket.class, (netConnection, pingCallPacket) -> {
            pingMap.put(
                    netConnection.player.uuid(),
                    (int) (Time.timeSinceMillis(pingCallPacket.time) * 2)//go and back
            );
        });
    }

    @Override
    public Map<String, Integer> poll() {
        return pingMap;
    }
}

