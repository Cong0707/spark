package me.lucko.spark.common.command.modules;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import mindustry.gen.Player;

import java.util.function.Consumer;

public class ColorModule implements CommandModule {
    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("color", "c")
                .allowSubCommand(true)
                .argumentUsage("enable", "", null)
                .argumentUsage("disable", "", null)
                .executor(this::color)
                .build()
        );
    }

    private void color(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        String subCommand = arguments.subCommand() == null ? "" : arguments.subCommand();
        if (subCommand.equals("enable") || arguments.boolFlag("enable")) {
            platform.enableColor();
            resp.reply("Color display enabled");
            return;
        }
        if (subCommand.equals("disable") || arguments.boolFlag("disable")) {
            platform.disableColor();
            resp.reply("Color display disabled");
            return;
        }
        if (arguments.raw().isEmpty()) {
            if (platform.isColorEnable()) {
                resp.reply("Color display is enable");
            } else {
                resp.reply("Color display is disable");
            }
        }
    }
}
