package io.github.cong;

import arc.Core;
import arc.util.CommandHandler;
import arc.util.Log;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.SparkPlugin;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import me.lucko.spark.common.monitor.tick.TickStatistics;
import me.lucko.spark.common.platform.PlatformInfo;
import mindustry.Vars;
import mindustry.entities.EntityGroup;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

import java.nio.file.Path;
import java.util.logging.Level;

import static mindustry.Vars.dataDirectory;

@SuppressWarnings("unused")
public class MindustrySparkPlugin extends Plugin implements SparkPlugin {

    private SparkPlatform platform;

    //called when game initializes
    @Override
    public void init(){
        this.platform = new SparkPlatform(this);
        this.platform.enable();
    }

    //register commands that run on the server
    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("tps", "Show tps dialog.", args -> this.platform.executeCommand(null, new String[]{"tps"}));
        handler.register("spark", "[args...]","Spark command.", args -> {

            StringBuilder result = new StringBuilder();

            for (int i = 0; i < args.length; i++) {
                result.append(args[i]);

                if (i < args.length - 1) {
                    result.append(" ");
                }
            }

            this.platform.executeCommand(null, result.toString().split(" "));
        });
    }

    //register commands that player can invoke in-game
    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("tps", "Show tps dialog.", (args, player) -> this.platform.executeCommand(player, new String[]{"tps"}));
        handler.<Player>register("spark", "[args...]","Spark command.", (args, player) -> {
            if (player.admin()) {

                StringBuilder result = new StringBuilder();

                for (int i = 0; i < args.length; i++) {
                    result.append(args[i]);

                    if (i < args.length - 1) {
                        result.append(" ");
                    }
                }

                this.platform.executeCommand(player, result.toString().split(" "));
            } else {
                player.sendMessage("[red]You do not have permission to use this command.");
            }
        });
    }

    @Override
    public String getVersion() {
        return Vars.mods.getMod(this.getClass()).meta.version;
    }
    @Override
    public Path getPluginDirectory() {
        return dataDirectory.child("mods").child("spark").file().toPath();
    }

    @Override
    public String getCommandName() {
        return "spark";
    }

    @Override
    public EntityGroup<Player> getCommandSenders() {
        return Groups.player;
    }

    @Override
    public void executeAsync(Runnable task) {
        Core.app.post(task);
    }

    @Override
    public void executeSync(Runnable task) {
        Core.app.post(task);
    }

    @Override
    public void log(Level level, String msg) {
        if ( level != Level.WARNING ) {
            Log.warn("[Spark]" + msg);
        } else {
            Log.info("[Spark]" + msg);
        }
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new MindustryPlatformInfo();
    }

    @Override
    public TickStatistics createTickStatistics() {
        return new MindustryTickStatistics();
    }

    @Override
    public PlayerPingProvider createPlayerPingProvider() {
        return new MindustryPlayerPingProvider();
    }
}
