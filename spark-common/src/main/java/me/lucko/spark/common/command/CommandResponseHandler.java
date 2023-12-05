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
import mindustry.entities.EntityGroup;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.function.Consumer;

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
            Log.info(Strings.stripColors(message));
        }else{
            this.sender.sendMessage(message);
        }
    }

    public void broadcast(String message) {
        if (this.platform.shouldBroadcastResponse()) {
            Groups.player.forEach(player -> player.sendMessage(message));
            Log.info(Strings.stripColors(message));
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
