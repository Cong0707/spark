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
import me.lucko.spark.common.activitylog.Activity;
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.heapdump.HeapDump;
import me.lucko.spark.common.heapdump.HeapDumpSummary;
import me.lucko.spark.common.util.Compression;
import me.lucko.spark.common.util.FormatUtil;
import me.lucko.spark.common.util.MediaTypes;
import me.lucko.spark.proto.SparkHeapProtos;
import mindustry.gen.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;


public class HeapAnalysisModule implements CommandModule {

    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("heapsummary")
                .argumentUsage("save-to-file", null)
                .executor(HeapAnalysisModule::heapSummary)
                .build()
        );

        consumer.accept(Command.builder()
                .aliases("heapdump")
                .argumentUsage("compress", "type")
                .executor((platform1, resp, arguments1, arguments12) -> heapDump(platform1, arguments1, arguments12))
                .build()
        );
    }

    private static void heapSummary(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        if (arguments.boolFlag("run-gc-before")) {
            resp.broadcastPrefixed("Running garbage collector...");
            System.gc();
        }

        resp.broadcastPrefixed("Creating a new heap dump summary, please wait...");

        HeapDumpSummary heapDump;
        try {
            heapDump = HeapDumpSummary.createNew();
        } catch (Exception e) {
            resp.broadcastPrefixed("[red]An error occurred whilst inspecting the heap.");
            e.printStackTrace();
            return;
        }

        SparkHeapProtos.HeapData output = heapDump.toProto(platform, sender);

        boolean saveToFile = false;
        if (arguments.boolFlag("save-to-file")) {
            saveToFile = true;
        } else {
            try {
                String key = platform.getBytebinClient().postContent(output, MediaTypes.SPARK_HEAP_MEDIA_TYPE).key();
                String url = platform.getViewerUrl() + key;

                resp.broadcastPrefixed("[gold]Heap dump summmary output:");
                resp.broadcast("[gray]" + url);

                platform.getActivityLog().addToLog(Activity.urlActivity(System.currentTimeMillis(), "Heap dump summary", url));
            } catch (Exception e) {
                resp.broadcastPrefixed("[red]An error occurred whilst uploading the data. Attempting to save to disk instead.");
                e.printStackTrace();
                saveToFile = true;
            }
        }

        if (saveToFile) {
            Path file = platform.resolveSaveFile("heapsummary", "sparkheap");
            try {
                Files.write(file, output.toByteArray());

                resp.broadcastPrefixed("Heap dump summary written to: [gold]" + file.toString() + "[gray]");
                resp.broadcastPrefixed("[gray]You can read the heap dump summary file using the viewer web-app - " + platform.getViewerUrl());

                platform.getActivityLog().addToLog(Activity.fileActivity(System.currentTimeMillis(), "Heap dump summary", file.toString()));
            } catch (IOException e) {
                resp.broadcastPrefixed("[red]An error occurred whilst saving the data.");
                e.printStackTrace();
            }
        }

    }

    private static void heapDump(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        Path file = platform.resolveSaveFile("heap", HeapDump.isOpenJ9() ? "phd" : "hprof");

        boolean liveOnly = !arguments.boolFlag("include-non-live");

        if (arguments.boolFlag("run-gc-before")) {
            resp.broadcastPrefixed("Running garbage collector...");
            System.gc();
        }

        resp.broadcastPrefixed("Creating a new heap dump, please wait...");

        try {
            HeapDump.dumpHeap(file, liveOnly);
        } catch (Exception e) {
            resp.broadcastPrefixed("[red]An error occurred whilst creating a heap dump.");
            e.printStackTrace();
            return;
        }

        resp.broadcastPrefixed("[gold]Heap dump written to: [gray]" + file.toString());
        platform.getActivityLog().addToLog(Activity.fileActivity(System.currentTimeMillis(), "Heap dump", file.toString()));


        Compression compressionMethod = null;
        Iterator<String> compressArgs = arguments.stringFlag("compress").iterator();
        if (compressArgs.hasNext()) {
            try {
                compressionMethod = Compression.valueOf(compressArgs.next().toUpperCase());
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }

        if (compressionMethod != null) {
            try {
                heapDumpCompress(platform, resp, file, compressionMethod);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void heapDumpCompress(SparkPlatform platform, CommandResponseHandler resp, Path file, Compression method) throws IOException {
        resp.broadcastPrefixed("Compressing heap dump, please wait...");

        long size = Files.size(file);
        AtomicLong lastReport = new AtomicLong(System.currentTimeMillis());

        LongConsumer progressHandler = progress -> {
            long timeSinceLastReport = System.currentTimeMillis() - lastReport.get();
            if (timeSinceLastReport > TimeUnit.SECONDS.toMillis(5)) {
                lastReport.set(System.currentTimeMillis());

                platform.getPlugin().executeAsync(() -> {
                    resp.broadcastPrefixed("[gray]Compressed [gold]" + FormatUtil.formatBytes(progress) + "[gray] / [gold]" + FormatUtil.formatBytes(size) + "[gray] so far... ([green]" + FormatUtil.percent(progress, size) + "[gray])");
                });
            }
        };

        Path compressedFile = method.compress(file, progressHandler);
        long compressedSize = Files.size(compressedFile);

        resp.broadcastPrefixed("[gray]Compression complete: [gold]" + FormatUtil.formatBytes(size) + "[gray] --> [gold]" + FormatUtil.formatBytes(compressedSize) + "[gray] ([green]" + FormatUtil.percent(compressedSize, size) + "[gray])");

        resp.broadcastPrefixed("[gold]Compressed heap dump written to: [gray]" + compressedFile.toString());
    }

}
