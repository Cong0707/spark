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

package me.lucko.spark.common.monitor.tick;

import com.sun.management.GarbageCollectionNotificationInfo;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.monitor.memory.GarbageCollectionMonitor;
import me.lucko.spark.common.tick.TickHook;

import java.text.DecimalFormat;
import java.util.DoubleSummaryStatistics;

/**
 * Monitoring process for the server/client tick rate.
 */
public abstract class TickMonitor implements TickHook.Callback, GarbageCollectionMonitor.Listener, AutoCloseable {
    private static final DecimalFormat DF = new DecimalFormat("#.##");

    /** The spark platform */
    private final SparkPlatform platform;
    /** The tick hook being used as the source for tick information. */
    private final TickHook tickHook;
    /** The index of the tick when the monitor first started */
    private final int zeroTick;
    /** The active garbage collection monitor, if enabled */
    private final GarbageCollectionMonitor garbageCollectionMonitor;
    /** The predicate used to decide if a tick should be reported. */
    private final ReportPredicate reportPredicate;

    /**
     * Enum representing the various phases in a tick monitors lifetime.
     */
    private enum Phase {
        /** Tick monitor is in the setup phase where it determines the average tick rate. */
        SETUP,
        /** Tick monitor is in the monitoring phase where it listens for ticks that exceed the threshold. */
        MONITORING
    }

    /** The phase the monitor is in */
    private Phase phase = null;
    /** Gets the system timestamp of the last recorded tick */
    private volatile double lastTickTime = 0;
    /** Used to calculate the average tick time during the SETUP phase. */
    private final DoubleSummaryStatistics averageTickTimeCalc = new DoubleSummaryStatistics();
    /** The average tick time, defined at the end of the SETUP phase. */
    private double averageTickTime;

    public TickMonitor(SparkPlatform platform, TickHook tickHook, ReportPredicate reportPredicate, boolean monitorGc) {
        this.platform = platform;
        this.tickHook = tickHook;
        this.zeroTick = tickHook.getCurrentTick();
        this.reportPredicate = reportPredicate;

        if (monitorGc) {
            this.garbageCollectionMonitor =  new GarbageCollectionMonitor();
            this.garbageCollectionMonitor.addListener(this);
        } else {
            this.garbageCollectionMonitor = null;
        }
    }

    public int getCurrentTick() {
        return this.tickHook.getCurrentTick() - this.zeroTick;
    }

    protected abstract void sendMessage(String message);

    public void start() {
        this.tickHook.addCallback(this);
    }

    @Override
    public void close() {
        this.tickHook.removeCallback(this);

        if (this.garbageCollectionMonitor != null) {
            this.garbageCollectionMonitor.close();
        }
    }

    @Override
    public void onTick(int currentTick) {
        double now = ((double) System.nanoTime()) / 1000000d;

        // init
        if (this.phase == null) {
            this.phase = Phase.SETUP;
            this.lastTickTime = now;
            sendMessage("Tick monitor started. Before the monitor becomes fully active, the server's " +
                    "average tick rate will be calculated over a period of 120 ticks (approx 6 seconds).");
            return;
        }

        // find the diff
        double last = this.lastTickTime;
        double tickDuration = now - last;
        this.lastTickTime = now;

        if (last == 0) {
            return;
        }

        // form averages
        if (this.phase == Phase.SETUP) {
            this.averageTickTimeCalc.accept(tickDuration);

            // move onto the next state
            if (this.averageTickTimeCalc.getCount() >= 120) {
                this.platform.getPlugin().executeAsync(() -> {
                    sendMessage("[gold]Analysis is now complete.");
                    sendMessage("[gray]> [white]Max: [gray]" + DF.format(this.averageTickTimeCalc.getMax()) + "ms");
                    sendMessage("[gray]> [white]Min: [gray]" + DF.format(this.averageTickTimeCalc.getMin()) + "ms");
                    sendMessage("[gray]> [white]Average: [gray]" + DF.format(this.averageTickTimeCalc.getAverage()) + "ms");
                    sendMessage(this.reportPredicate.monitoringStartMessage());
                });

                this.averageTickTime = this.averageTickTimeCalc.getAverage();
                this.phase = Phase.MONITORING;
            }
        }

        if (this.phase == Phase.MONITORING) {
            double increase = tickDuration - this.averageTickTime;
            double percentageChange = (increase * 100d) / this.averageTickTime;
            if (this.reportPredicate.shouldReport(tickDuration, increase, percentageChange)) {
                this.platform.getPlugin().executeAsync(() -> {
                    sendMessage("[gray]Tick [dark_gray]#" + getCurrentTick() + "[gray] lasted [gold]" + DF.format(tickDuration) +
                            " ms. ([gold]" + DF.format(percentageChange) + "%[gold] increase from avg)");
                });
            }
        }
    }

    @Override
    public void onGc(GarbageCollectionNotificationInfo data) {
        if (this.phase == Phase.SETUP) {
            // set lastTickTime to zero so this tick won't be counted in the average
            this.lastTickTime = 0;
            return;
        }

        this.platform.getPlugin().executeAsync(() -> {
            sendMessage("[gray]Tick [dark_gray]#" + getCurrentTick() + "[gray] included [red]GC[gray] lasting [gold]" +
                    DF.format(data.getGcInfo().getDuration()) + " ms. (type = " + GarbageCollectionMonitor.getGcType(data) + ")");
        });
    }

}
