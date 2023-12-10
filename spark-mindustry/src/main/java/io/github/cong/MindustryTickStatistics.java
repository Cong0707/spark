package io.github.cong;

import arc.Core;
import arc.util.Time;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.common.monitor.tick.TickStatistics;
import me.lucko.spark.common.util.RollingAverage;
import mindustry.Vars;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class MindustryTickStatistics implements TickStatistics {

    private static final long SEC_IN_NANO = TimeUnit.SECONDS.toNanos(1);
    private static final int TPS = 100;

    private final TpsRollingAverage tps5Sec = new TpsRollingAverage(5);
    private final TpsRollingAverage tps10Sec = new TpsRollingAverage(10);
    private final TpsRollingAverage tps1Min = new TpsRollingAverage(60);
    private final TpsRollingAverage tps5Min = new TpsRollingAverage(60 * 5);
    private final TpsRollingAverage tps15Min = new TpsRollingAverage(60 * 15);
    private final TpsRollingAverage[] tpsAverages = {this.tps5Sec, this.tps10Sec, this.tps1Min, this.tps5Min, this.tps15Min};

    private final RollingAverage tickDuration10Sec = new RollingAverage(TPS * 10);
    private final RollingAverage tickDuration1Min = new RollingAverage(TPS * 60);
    private final RollingAverage tickDuration5Min = new RollingAverage(TPS * 60 * 5);
    private final RollingAverage[] tickDurationAverages = {this.tickDuration10Sec, this.tickDuration1Min, this.tickDuration5Min};

    private long last = 0;

    @Override
    public boolean isDurationSupported() {
        return true;
    }

    MindustryTickStatistics(ScheduledExecutorService executor) {
        Runnable tpsTask = () -> {
            if (!Vars.state.isPlaying()) {
                return;
            }

            long now = System.nanoTime();

            long diff = now - this.last;
            BigDecimal currentTps = BigDecimal.valueOf(Core.graphics.getFramesPerSecond());
            BigDecimal total = currentTps.multiply(new BigDecimal(diff));

            for (TpsRollingAverage rollingAverage : this.tpsAverages) {
                rollingAverage.add(currentTps, diff, total);
            }

            BigDecimal duration = BigDecimal.valueOf(1000/(60/Time.delta));

            for (RollingAverage rollingAverage : this.tickDurationAverages) {
                rollingAverage.add(duration);
            }

            this.last = now;
        };

        Runnable durationTask = () -> {
            if (!Vars.state.isPlaying()) {
                return;
            }

            long now = System.nanoTime();

            long diff = now - this.last;
            BigDecimal currentTps = BigDecimal.valueOf(Core.graphics.getFramesPerSecond());
            BigDecimal total = currentTps.multiply(new BigDecimal(diff));

            for (TpsRollingAverage rollingAverage : this.tpsAverages) {
                rollingAverage.add(currentTps, diff, total);
            }

            BigDecimal duration = BigDecimal.valueOf(1000/(60/Time.delta));

            for (RollingAverage rollingAverage : this.tickDurationAverages) {
                rollingAverage.add(duration);
            }

            this.last = now;
        };

        executor.scheduleAtFixedRate(tpsTask, 0, 10, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(durationTask, 0, 10, TimeUnit.MILLISECONDS);
    }

    @Override
    public double tps5Sec() {
        return this.tps5Sec.getAverage();
    }

    @Override
    public double tps10Sec() {
        return this.tps10Sec.getAverage();
    }

    @Override
    public double tps1Min() {
        return this.tps1Min.getAverage();
    }

    @Override
    public double tps5Min() {
        return this.tps5Min.getAverage();
    }

    @Override
    public double tps15Min() {
        return this.tps15Min.getAverage();
    }

    @Override
    public DoubleAverageInfo duration10Sec() {
        return this.tickDuration10Sec;
    }

    @Override
    public DoubleAverageInfo duration1Min() {
        return this.tickDuration1Min;
    }

    @Override
    public DoubleAverageInfo duration5Min() {
        return this.tickDuration5Min;
    }


    /**
     * Rolling average calculator.
     *
     * <p>This code is taken from PaperMC/Paper, licensed under MIT.</p>
     *
     * @author aikar (PaperMC) <a href="https://github.com/PaperMC/Paper/blob/master/Spigot-Server-Patches/0021-Further-improve-server-tick-loop.patch">source</a>
     */
    public static final class TpsRollingAverage {
        private final int size;
        private long time;
        private BigDecimal total;
        private int index = 0;
        private final BigDecimal[] samples;
        private final long[] times;

        TpsRollingAverage(int size) {
            this.size = size;
            this.time = size * SEC_IN_NANO;
            this.total = new BigDecimal(TPS).multiply(new BigDecimal(SEC_IN_NANO)).multiply(new BigDecimal(size));
            this.samples = new BigDecimal[size];
            this.times = new long[size];
            for (int i = 0; i < size; i++) {
                this.samples[i] = new BigDecimal(TPS);
                this.times[i] = SEC_IN_NANO;
            }
        }

        public void add(BigDecimal x, long t, BigDecimal total) {
            this.time -= this.times[this.index];
            this.total = this.total.subtract(this.samples[this.index].multiply(new BigDecimal(this.times[this.index])));
            this.samples[this.index] = x;
            this.times[this.index] = t;
            this.time += t;
            this.total = this.total.add(total);
            if (++this.index == this.size) {
                this.index = 0;
            }
        }

        public double getAverage() {
            return this.total.divide(new BigDecimal(this.time), 30, RoundingMode.HALF_UP).doubleValue();
        }
    }

}
