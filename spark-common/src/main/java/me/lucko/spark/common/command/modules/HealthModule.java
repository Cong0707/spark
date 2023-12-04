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

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.monitor.cpu.CpuMonitor;
import me.lucko.spark.common.monitor.disk.DiskUsage;
import me.lucko.spark.common.monitor.net.Direction;
import me.lucko.spark.common.monitor.net.NetworkInterfaceAverages;
import me.lucko.spark.common.monitor.net.NetworkMonitor;
import me.lucko.spark.common.monitor.ping.PingStatistics;
import me.lucko.spark.common.monitor.ping.PingSummary;
import me.lucko.spark.common.monitor.tick.TickStatistics;
import me.lucko.spark.common.util.FormatUtil;
import me.lucko.spark.common.util.RollingAverage;
import me.lucko.spark.common.util.StatisticFormatter;
import mindustry.gen.Player;

import java.lang.management.*;
import java.util.*;
import java.util.function.Consumer;

public class HealthModule implements CommandModule {

    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("tps", "cpu")
                .executor(HealthModule::tps)
                .tabCompleter(Command.TabCompleter.empty())
                .build()
        );

        consumer.accept(Command.builder()
                .aliases("ping")
                .argumentUsage("player", "username")
                .executor(HealthModule::ping)
                .build()
        );

        consumer.accept(Command.builder()
                .aliases("healthreport", "health", "ht")
                .argumentUsage("memory", null)
                .argumentUsage("network", null)
                .executor(HealthModule::healthReport)
                .build()
        );
    }

    private static void tps(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        TickStatistics tickStatistics = platform.getTickStatistics();
        if (tickStatistics != null) {
            resp.replyPrefixed("TPS from last 5s, 10s, 1m, 5m, 15m:");
            resp.replyPrefixed(" " + StatisticFormatter.formatTps(tickStatistics.tps5Sec()) + ", " +
                    StatisticFormatter.formatTps(tickStatistics.tps10Sec()) + ", " +
                    StatisticFormatter.formatTps(tickStatistics.tps1Min()) + ", " +
                    StatisticFormatter.formatTps(tickStatistics.tps5Min()) + ", " +
                    StatisticFormatter.formatTps(tickStatistics.tps15Min())
            );
            resp.replyPrefixed("");

            if (tickStatistics.isDurationSupported()) {
                resp.replyPrefixed("Tick durations (min/med/95%ile/max ms) from last 10s, 1m:");
                resp.replyPrefixed(" " + StatisticFormatter.formatTickDurations(tickStatistics.duration10Sec()) + ";  " +
                        StatisticFormatter.formatTickDurations(tickStatistics.duration1Min())
                );
                resp.replyPrefixed("");
            }
        }

        resp.replyPrefixed("CPU usage from last 10s, 1m, 15m:");
        resp.replyPrefixed(" " + StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad10SecAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad1MinAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad15MinAvg()) + "  [gray](system)"
        );
        resp.replyPrefixed(" " + StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad10SecAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad1MinAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad15MinAvg()) + "  [gray](process)"
        );
    }

    private static void ping(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        PingStatistics pingStatistics = platform.getPingStatistics();
        if (pingStatistics == null) {
            resp.replyPrefixed("Ping data is not available on this platform.");
            return;
        }

        // lookup for specific player
        Set<String> players = arguments.stringFlag("player");
        if (!players.isEmpty()) {
            for (String player : players) {
                PingStatistics.PlayerPing playerPing = pingStatistics.query(player);
                if (playerPing == null) {
                    resp.replyPrefixed("Ping data is not available for '" + player + "'.");
                } else {
                    resp.replyPrefixed("Player [white]" + playerPing.name() + "[gray] has " + StatisticFormatter.formatPingRtt(playerPing.ping()) + " ms ping.");
                }
            }
            return;
        }

        PingSummary summary = pingStatistics.currentSummary();
        RollingAverage average = pingStatistics.getPingAverage();

        if (summary.total() == 0 && average.getSamples() == 0) {
            resp.replyPrefixed("There is not enough data to show ping averages yet. Please try again later.");
            return;
        }

        resp.replyPrefixed("Average Pings (min/med/95%ile/max ms) from now, last 15m:");
        resp.replyPrefixed(" " + StatisticFormatter.formatPingRtts(summary.min(), summary.median(), summary.percentile95th(), summary.max()) + ";  " +
                StatisticFormatter.formatPingRtts(average.min(), average.median(), average.percentile95th(), average.max())
        );
    }

    private static void healthReport(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        resp.replyPrefixed("Generating server health report...");
        List<String> report = new LinkedList<>();
        report.add("");

        TickStatistics tickStatistics = platform.getTickStatistics();
        if (tickStatistics != null) {
            addTickStats(report, tickStatistics);
        }

        addCpuStats(report);

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        addBasicMemoryStats(report, memoryMXBean);

        if (arguments.boolFlag("memory")) {
            addDetailedMemoryStats(report, memoryMXBean);
        }

        addNetworkStats(report, arguments.boolFlag("network"));

        addDiskStats(report);

        report.forEach(resp::reply);
    }

    private static void addTickStats(List<String> report, TickStatistics tickStatistics) {
        report.add("[gray]> [gold]TPS from last 5s, 10s, 1m, 5m, 15m:");
        report.add("    " + StatisticFormatter.formatTps(tickStatistics.tps5Sec()) + ", " +
                StatisticFormatter.formatTps(tickStatistics.tps10Sec()) + ", " +
                StatisticFormatter.formatTps(tickStatistics.tps1Min()) + ", " +
                StatisticFormatter.formatTps(tickStatistics.tps5Min()) + ", " +
                StatisticFormatter.formatTps(tickStatistics.tps15Min())
        );
        report.add("");

        if (tickStatistics.isDurationSupported()) {
            report.add("[gray]> [gold]Tick durations (min/med/95%ile/max ms) from last 10s, 1m:");
            report.add("    " + StatisticFormatter.formatTickDurations(tickStatistics.duration10Sec()) + "; " +
                    StatisticFormatter.formatTickDurations(tickStatistics.duration1Min())
            );
            report.add("");
        }
    }

    private static void addCpuStats(List<String> report) {
        report.add("[gray]> [gold]CPU usage from last 10s, 1m, 15m:");
        report.add("    " + StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad10SecAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad1MinAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.systemLoad15MinAvg()) + "  (system)[gray]"
        );
        report.add("    " + StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad10SecAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad1MinAvg()) + ", " +
                StatisticFormatter.formatCpuUsage(CpuMonitor.processLoad15MinAvg()) + "  (process)[gray]"
        );
        report.add("");
    }

    private static void addBasicMemoryStats(List<String> report, MemoryMXBean memoryMXBean) {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        report.add("[gray]> [gold]Memory usage:");
        report.add("    [white]" + FormatUtil.formatBytes(heapUsage.getUsed()) + "[gray]/[white]" + FormatUtil.formatBytes(heapUsage.getMax()) +
                "   [gray](" + FormatUtil.percent(heapUsage.getUsed(), heapUsage.getMax()) + ")[green]"
        );
        report.add("    " + StatisticFormatter.generateMemoryUsageDiagram(heapUsage, 60));
        report.add("");
    }

    private static void addDetailedMemoryStats(List<String> report, MemoryMXBean memoryMXBean) {
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        report.add("[gray]>[reset] [gold]Non-heap memory usage:");
        report.add("    [white]" + FormatUtil.formatBytes(nonHeapUsage.getUsed()));
        report.add("");

        List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean memoryPool : memoryPoolMXBeans) {
            if (memoryPool.getType() != MemoryType.HEAP) {
                continue;
            }

            MemoryUsage usage = memoryPool.getUsage();
            MemoryUsage collectionUsage = memoryPool.getCollectionUsage();

            if (usage.getMax() == -1) {
                usage = new MemoryUsage(usage.getInit(), usage.getUsed(), usage.getCommitted(), usage.getCommitted());
            }

            report.add("[gray]> [gold]" + memoryPool.getName() + " pool usage:");
            report.add("    [white]" + FormatUtil.formatBytes(usage.getUsed()) + "[gray]/[white]" + FormatUtil.formatBytes(usage.getMax()) +
                    "   [gray](" + FormatUtil.percent(usage.getUsed(), usage.getMax()) + ")[gray]"
            );
            report.add("    " + StatisticFormatter.generateMemoryPoolDiagram(usage, collectionUsage, 60));

            if (collectionUsage != null) {
                report.add("     [red]- [gray]Usage at last GC: [white]" + FormatUtil.formatBytes(collectionUsage.getUsed()));
            }
            report.add("");
        }
    }

    private static void addNetworkStats(List<String> report, boolean detailed) {
        List<String> averagesReport = new LinkedList<>();

        for (Map.Entry<String, NetworkInterfaceAverages> ent : NetworkMonitor.systemAverages().entrySet()) {
            String interfaceName = ent.getKey();
            NetworkInterfaceAverages averages = ent.getValue();

            for (Direction direction : Direction.values()) {
                long bytesPerSec = (long) averages.bytesPerSecond(direction).mean();
                long packetsPerSec = (long) averages.packetsPerSecond(direction).mean();

                if (detailed || bytesPerSec > 0 || packetsPerSec > 0) {
                    averagesReport.add("[gray]    [green]" + FormatUtil.formatBytes(bytesPerSec, "green", "/s") +
                            "[white] / " + String.format(Locale.ENGLISH, "%,d", packetsPerSec) +
                            " pps [gray](" + interfaceName + " " + direction.abbrev() + ")[gray]");
                }
            }
        }

        if (!averagesReport.isEmpty()) {
            report.add("[gray]>[reset] [gold]Network usage: (system, last 15m)");
            report.addAll(averagesReport);
            report.add("");
        }
    }

    private static void addDiskStats(List<String> report) {
        long total = DiskUsage.getTotal();
        long used = DiskUsage.getUsed();
        
        if (total == 0 || used == 0) {
            return;
        }

        report.add("[gray]>[reset] [gold]Disk usage:");
        report.add("    [white]" + FormatUtil.formatBytes(used) + "[gray]/[white]" + FormatUtil.formatBytes(total) + "   [gray](" + FormatUtil.percent(used, total) + ")[gray]");
        report.add("    " + StatisticFormatter.generateDiskUsageDiagram(used, total, 60));
        report.add("");
    }

}
