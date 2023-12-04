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

package me.lucko.spark.common.command.modules;

import com.sun.management.GarbageCollectionNotificationInfo;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.monitor.memory.GarbageCollectionMonitor;
import me.lucko.spark.common.monitor.memory.GarbageCollectorStatistics;
import me.lucko.spark.common.util.FormatUtil;

import java.lang.management.MemoryUsage;
import java.text.DecimalFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class GcMonitoringModule implements CommandModule {
    private static final DecimalFormat DF = new DecimalFormat("#.##");

    /** The gc monitoring instance currently running, if any */
    private ReportingGcMonitor activeGcMonitor = null;

    @Override
    public void close() {
        if (this.activeGcMonitor != null) {
            this.activeGcMonitor.close();
            this.activeGcMonitor = null;
        }
    }

    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("gc")
                .executor((platform, sender, resp, arguments) -> {
                    resp.replyPrefixed("Calculating GC statistics...");

                    List<String> report = new LinkedList<>();
                    report.add("");
                    report.add("[gray]> [gold]Garbage Collector statistics");

                    long serverUptime = System.currentTimeMillis() - platform.getServerNormalOperationStartTime();
                    Map<String, GarbageCollectorStatistics> collectorStats = GarbageCollectorStatistics.pollStatsSubtractInitial(platform.getStartupGcStatistics());

                    for (Map.Entry<String, GarbageCollectorStatistics> collector : collectorStats.entrySet()) {
                        String collectorName = collector.getKey();
                        double collectionTime = collector.getValue().getCollectionTime();
                        long collectionCount = collector.getValue().getCollectionCount();

                        report.add("");

                        if (collectionCount == 0) {
                            report.add("    [gray][" + collectorName + "] collector:");
                            report.add("      [white]0[gray] collections");
                            continue;
                        }

                        double averageCollectionTime = collectionTime / collectionCount;
                        double averageFrequency = (serverUptime - collectionTime) / collectionCount;

                        report.add("    [gray]" + collectorName + " collector:");
                        report.add("    [gold]" + DF.format(averageCollectionTime) + "[gray] ms avg, [white]" + collectionCount + " [gray]total collections");
                        report.add("    [white]" + FormatUtil.formatSeconds((long) averageFrequency / 1000) + "[gray] avg frequency");
                    }

                    if (collectorStats.isEmpty()) {
                        resp.replyPrefixed("No garbage collectors are reporting data.");
                    } else {
                        report.forEach(resp::reply);
                    }
                })
                .build()
        );

        consumer.accept(Command.builder()
                .aliases("gcmonitor", "gcmonitoring")
                .executor((platform, sender, resp, arguments) -> {
                    if (this.activeGcMonitor == null) {
                        this.activeGcMonitor = new ReportingGcMonitor(platform, resp);
                        resp.broadcastPrefixed("GC monitor enabled.");
                    } else {
                        close();
                        resp.broadcastPrefixed("GC monitor disabled.");
                    }
                })
                .build()
        );
    }

    private static class ReportingGcMonitor extends GarbageCollectionMonitor implements GarbageCollectionMonitor.Listener {
        private final SparkPlatform platform;
        private final CommandResponseHandler resp;

        ReportingGcMonitor(SparkPlatform platform, CommandResponseHandler resp) {
            this.platform = platform;
            this.resp = resp;
            addListener(this);
        }

        @Override
        public void onGc(GarbageCollectionNotificationInfo data) {
            String gcType = GarbageCollectionMonitor.getGcType(data);
            String gcCause = data.getGcCause() != null ? " (cause = " + data.getGcCause() + ")" : "";

            Map<String, MemoryUsage> beforeUsages = data.getGcInfo().getMemoryUsageBeforeGc();
            Map<String, MemoryUsage> afterUsages = data.getGcInfo().getMemoryUsageAfterGc();

            this.platform.getPlugin().executeAsync(() -> {
                List<String> report = new LinkedList<>();
                report.add(CommandResponseHandler.applyPrefix("[gray]" + gcType + " [red]GC [gray] lasting [gold]" + DF.format(data.getGcInfo().getDuration()) + "[gray] ms." + gcCause));

                for (Map.Entry<String, MemoryUsage> entry : afterUsages.entrySet()) {
                    String type = entry.getKey();
                    MemoryUsage after = entry.getValue();
                    MemoryUsage before = beforeUsages.get(type);

                    if (before == null) {
                        continue;
                    }

                    long diff = before.getUsed() - after.getUsed();
                    if (diff == 0) {
                        continue;
                    }

                    if (diff > 0) {
                        report.add("  [gold]" + FormatUtil.formatBytes(diff) + "[gray] freed from [gray]" + type);
                        report.add("  [gray]" + FormatUtil.formatBytes(before.getUsed()) + "[gray] → " + FormatUtil.formatBytes(after.getUsed()) + " " + "([white]" + FormatUtil.percent(diff, before.getUsed()) + ")");
                    } else {
                        report.add("  [gold]" + FormatUtil.formatBytes(-diff) + "[gray] moved to " + type);
                        report.add("  [gray]" + FormatUtil.formatBytes(before.getUsed()) + "[gray] → " + FormatUtil.formatBytes(after.getUsed()));
                    }
                }
                report.forEach(this.resp::broadcast);
            });
        }

    }
}
