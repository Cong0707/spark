package io.github.cong;

import arc.func.Cons2;
import arc.util.Log;
import arc.util.Time;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import mindustry.Vars;
import mindustry.gen.PingCallPacket;
import mindustry.net.NetConnection;
import arc.struct.ObjectMap;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MindustryPlayerPingProvider implements PlayerPingProvider {
    Map<String, Integer> pingMap = new HashMap<>();
    private ObjectMap<Class<?>, Cons2<NetConnection, Object>> serverListeners;

    public Cons2<NetConnection, Object> getPacketHandle(Class<PingCallPacket> packet) {
        Cons2<NetConnection, Object> got = serverListeners.get(packet);
        Cons2<NetConnection, Object> def = (con, p) -> {
            if (p instanceof PingCallPacket) {
                ((PingCallPacket) p).handleServer(con);
            }
        };
        return got != null ? got : def;
    }
    MindustryPlayerPingProvider() {
        Class<?> clazz = mindustry.net.Net.class;
        Field field;
        try {
            field = clazz.getDeclaredField("serverListeners");
            field.setAccessible(true);
            serverListeners = (ObjectMap<Class<?>, Cons2<NetConnection, Object>>) field.get(Vars.net);
        } catch (Exception e) {
            Log.err("Expection in reflect in MindustryPlayerPingProvider", e);
        }
        Cons2<NetConnection, Object> old = getPacketHandle(PingCallPacket.class);
        Vars.net.handleServer(PingCallPacket.class, (netConnection, pingCallPacket) -> {
            pingMap.put(
                    netConnection.player.uuid(),
                    (int) (Time.timeSinceMillis(pingCallPacket.time))
            );
            old.get(netConnection, pingCallPacket);
        });
    }
    @Override
    public Map<String, Integer> poll() {
        return pingMap;
    }
}

