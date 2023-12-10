/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.command;

import arc.util.Log;
import arc.util.Strings;
import me.lucko.spark.common.SparkPlatform;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandResponseHandler {

    /** The prefix used in all messages "&8[&e&l⚡&8] &7" */
    private static final String PREFIX = "[gray][[[yellow]⚡[gray]][white] ";

    private final SparkPlatform platform;
    private final Player sender;

    public CommandResponseHandler(SparkPlatform platform, Player sender) {
        this.platform = platform;
        this.sender = sender;
    }

    public Player sender() {
        return this.sender;
    }

    public void reply(String message) {
        if (sender == null){
            if (platform.isColorEnable()) {
                Log.info(Strings.stripColors(ColorApi.handle(message, ColorApi::consoleColorHandler)).replace("[[", "["));
            } else {
                Log.info(Strings.stripColors(message).replace("[[", "["));
            }
        }else{
            this.sender.sendMessage(message);
        }
    }

    public void broadcast(String message) {
        if (this.platform.shouldBroadcastResponse()) {
            Groups.player.forEach(player -> player.sendMessage(message));
            if (platform.isColorEnable()) {
                Log.info(Strings.stripColors(ColorApi.handle(message, ColorApi::consoleColorHandler)).replace("[[", "["));
            } else {
                Log.info(Strings.stripColors(message).replace("[[", "["));
            }
        } else {
            reply(message);
        }
    }

    public void replyPrefixed(String message) {
        reply(applyPrefix(message));
    }

    public void broadcastPrefixed(String message) {
        broadcast(applyPrefix(message));
    }

    public static String applyPrefix(String message) {
        return PREFIX + message;
    }

}

enum ConsoleColor implements ColorApi.Color {
    RESET("\u001b[0m"),
    BOLD("\u001b[1m"),
    ITALIC("\u001b[3m"),
    UNDERLINED("\u001b[4m"),

    BLACK("\u001b[30m"),
    RED("\u001b[31m"),
    GREEN("\u001b[32m"),
    ACID("\u001b[32m"),
    YELLOW("\u001b[33m"),
    GOLD("\u001b[33m"),
    BLUE("\u001b[34m"),
    PURPLE("\u001b[35m"),
    CYAN("\u001b[36m"),
    LIGHT_RED("\u001b[91m"),
    LIGHT_GREEN("\u001b[92m"),
    LIGHT_YELLOW("\u001b[93m"),
    LIGHT_BLUE("\u001b[94m"),
    LIGHT_PURPLE("\u001b[95m"),
    LIGHT_CYAN("\u001b[96m"),
    WHITE("\u001b[37m"),
    BACK_DEFAULT("\u001b[49m"),
    BACK_RED("\u001b[41m"),
    BACK_GREEN("\u001b[42m"),
    BACK_YELLOW("\u001b[43m"),
    BACK_BLUE("\u001b[44m");

    @Override
    public String toString() {
        return "[" + name() + "]";
    }

    ConsoleColor(String code) {
        this.code = code;
    }

    final String code;
}

class ColorApi {
    interface Color {}

    private static final Map<String, Color> map = new HashMap<>(); // name(Upper)->source

    public static void register(String name, Color color) {
        map.put(name.toUpperCase(), color);
    }

    static {
        for (ConsoleColor consoleColor : ConsoleColor.values()) {
            register(consoleColor.name(), consoleColor);
        }
    }

    public static String consoleColorHandler(Color color) {
        if (color instanceof ConsoleColor) {
            return ((ConsoleColor) color).code;
        } else {
            return "";
        }
    }

    public static String handle(String raw, Function<Color, String> colorHandler) {
        Pattern pattern = Pattern.compile("\\[([!a-zA-Z_]+)]");
        Matcher matcher = pattern.matcher(raw);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String matched = matcher.group(1);
            if (matched.startsWith("!")) {
                matcher.appendReplacement(result, "[ " + matched.substring(1) + "]");
            } else {
                Color color = map.get(matched.toUpperCase());
                if (color != null) {
                    String replacement = colorHandler.apply(color);
                    matcher.appendReplacement(result, replacement);
                } else {
                    matcher.appendReplacement(result, matcher.group());
                }
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }
}