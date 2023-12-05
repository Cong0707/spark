package io.github.cong;

import arc.util.Time;
import arc.util.Timer;
import me.lucko.spark.common.tick.AbstractTickHook;
import me.lucko.spark.common.tick.TickHook;

public class MindustryTickHook extends AbstractTickHook implements TickHook, Runnable {

    private Timer.Task task;

    @Override
    public void run() {
        onTick();
    }

    @Override
    public void start() {
        this.task = Time.runTask(1, this);
    }

    @Override
    public void close() {
        this.task.cancel();
    }
}