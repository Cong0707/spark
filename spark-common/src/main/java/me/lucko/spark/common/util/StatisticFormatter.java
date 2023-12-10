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

package me.lucko.spark.common.util;

import com.google.common.base.Strings;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;

import java.lang.management.MemoryUsage;
import java.util.Locale;

public enum StatisticFormatter {
    ;

    private static final String BAR_TRUE_CHARACTER = "┃";
    private static final String BAR_FALSE_CHARACTER = "╻";

    public static String formatTps(double tps) {
        String color;
        if (tps > 50.0) {
            color = "[acid]";
        } else if (tps > 30.0) {
            color = "[yellow]";
        } else {
            color = "[red]";
        }

        return color + (tps > 100.0 ? "*" : "") + Math.min(Math.round(tps * 100.0) / 100.0, 60.0);
    }

    public static String formatTickDurations(DoubleAverageInfo average) {
        return formatTickDuration(average.min()) + "/" +
                formatTickDuration(average.median()) + "/" +
                formatTickDuration(average.percentile95th()) + "/" +
                formatTickDuration(average.max());
    }

    public static String formatTickDuration(double duration) {
        String color;
        if (duration >= 50d) {
            color = "[red]";
        } else if (duration >= 40d) {
            color = "[yellow]";
        } else {
            color = "[acid]";
        }

        return color + String.format(Locale.ENGLISH, "%.1f", duration);
    }

    public static String formatCpuUsage(double usage) {
        String color;
        if (usage > 0.9) {
            color = "[red]";
        } else if (usage > 0.65) {
            color = "[yellow]";
        } else {
            color = "[acid]";
        }

        return color + FormatUtil.percent(usage, 1d);
    }

    public static String formatPingRtts(double min, double median, double percentile95th, double max) {
        return formatPingRtt(min) + "/" +
                formatPingRtt(median) + "/" +
                formatPingRtt(percentile95th) + "/" +
                formatPingRtt(max);
    }

    public static String formatPingRtt(double ping) {
        String color;
        if (ping >= 200) {
            color = "[red]";
        } else if (ping >= 100) {
            color = "[yellow]";
        } else {
            color = "[acid]";
        }

        return color + (int) Math.ceil(ping);
    }

    public static String generateMemoryUsageDiagram(MemoryUsage usage, int length) {
        double used = usage.getUsed();
        double committed = usage.getCommitted();
        double max = usage.getMax();

        int usedChars = (int) ((used * length) / max);
        int committedChars = (int) ((committed * length) / max);

        String line = Strings.repeat(BAR_TRUE_CHARACTER, usedChars);
        if (committedChars > usedChars) {
            line += Strings.repeat(BAR_FALSE_CHARACTER, (committedChars - usedChars) - 1);
            line += BAR_FALSE_CHARACTER;
        }
        if (length > committedChars) {
            line += Strings.repeat(BAR_FALSE_CHARACTER, (length - committedChars));
        }

        return "[gray][" + line + "[gray]]";
    }

    public static String generateMemoryPoolDiagram(MemoryUsage usage, MemoryUsage collectionUsage, int length) {
        double used = usage.getUsed();
        double collectionUsed = used;
        if (collectionUsage != null) {
            collectionUsed = collectionUsage.getUsed();
        }
        double committed = usage.getCommitted();
        double max = usage.getMax();

        int usedChars = (int) ((used * length) / max);
        int collectionUsedChars = (int) ((collectionUsed * length) / max);
        int committedChars = (int) ((committed * length) / max);

        String line = Strings.repeat(BAR_TRUE_CHARACTER, collectionUsedChars);

        if (usedChars > collectionUsedChars) {
            line += BAR_TRUE_CHARACTER;
            line += Strings.repeat(BAR_TRUE_CHARACTER, (usedChars - collectionUsedChars) - 1);
        }
        if (committedChars > usedChars) {
            line += Strings.repeat(BAR_FALSE_CHARACTER, (committedChars - usedChars) - 1);
            line += BAR_FALSE_CHARACTER;
        }
        if (length > committedChars) {
            line += Strings.repeat(BAR_FALSE_CHARACTER, (length - committedChars));
        }

        return "[" + line + "]";
    }

    public static String generateDiskUsageDiagram(double used, double max, int length) {
        int usedChars = (int) ((used * length) / max);
        int freeChars = length - usedChars;
        return "[gray][" + Strings.repeat(BAR_TRUE_CHARACTER, usedChars) + "[yellow]" +
                Strings.repeat(BAR_FALSE_CHARACTER, freeChars) + "[gray]]";
    }
}
