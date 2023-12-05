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

import com.google.common.collect.Iterables;
import me.lucko.bytesocks.client.BytesocksClient;
import me.lucko.spark.common.Data;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.activitylog.Activity;
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.sampler.*;
import me.lucko.spark.common.sampler.async.AsyncSampler;
import me.lucko.spark.common.sampler.node.MergeMode;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.util.FormatUtil;
import me.lucko.spark.common.util.MediaTypes;
import me.lucko.spark.common.util.MethodDisambiguator;
import me.lucko.spark.common.ws.ViewerSocket;
import me.lucko.spark.proto.SparkSamplerProtos;
import mindustry.gen.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SamplerModule implements CommandModule {

    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("profiler", "sampler")
                .allowSubCommand(true)
                .argumentUsage("info", "", null)
                .argumentUsage("open", "", null)
                .argumentUsage("start", "timeout", "timeout seconds")
                .argumentUsage("start", "thread *", null)
                .argumentUsage("start", "thread", "thread name")
                .argumentUsage("start", "only-ticks-over", "tick length millis")
                .argumentUsage("start", "interval", "interval millis")
                .argumentUsage("start", "alloc", null)
                .argumentUsage("stop", "", null)
                .argumentUsage("cancel", "", null)
                .executor(this::profiler)
                .build()
        );
    }

    private void profiler(SparkPlatform platform, Player sender, CommandResponseHandler resp, Arguments arguments) {
        String subCommand = arguments.subCommand() == null ? "" : arguments.subCommand();

        if (subCommand.equals("info") || arguments.boolFlag("info")) {
            profilerInfo(platform, resp);
            return;
        }

        if (subCommand.equals("open") || arguments.boolFlag("open")) {
            profilerOpen(platform, resp, arguments);
            return;
        }

        if (subCommand.equals("trust-viewer") || arguments.boolFlag("trust-viewer")) {
            profilerTrustViewer(platform, resp, arguments);
            return;
        }

        if (subCommand.equals("cancel") || arguments.boolFlag("cancel")) {
            profilerCancel(platform, resp);
            return;
        }

        if (subCommand.equals("stop") || subCommand.equals("upload") || arguments.boolFlag("stop") || arguments.boolFlag("upload")) {
            profilerStop(platform, resp, arguments);
            return;
        }

        if (subCommand.equals("start") || arguments.boolFlag("start")) {
            profilerStart(platform, resp, arguments);
            return;
        }

        if (arguments.raw().isEmpty()) {
            profilerInfo(platform, resp);
        } else {
            profilerStart(platform, resp, arguments);
        }
    }

    private void profilerStart(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        Sampler previousSampler = platform.getSamplerContainer().getActiveSampler();
        if (previousSampler != null) {
            if (previousSampler.isRunningInBackground()) {
                // there is a background profiler running - stop that first
                resp.replyPrefixed("Stopping the background profiler before starting... please wait");
                previousSampler.stop(true);
                platform.getSamplerContainer().unsetActiveSampler(previousSampler);
            } else {
                // there is a non-background profiler running - tell the user
                profilerInfo(platform, resp);
                return;
            }
        }

        int timeoutSeconds = arguments.intFlag("timeout");
        if (timeoutSeconds != -1 && timeoutSeconds <= 10) {
            resp.replyPrefixed("The specified timeout is not long enough for accurate results to be formed. " +
                    "[red]Please choose a value greater than 10.");
            return;
        }

        if (timeoutSeconds != -1 && timeoutSeconds < 30) {
            resp.replyPrefixed("The accuracy of the output will significantly improve when the profiler is able to run for longer periods. " +
                    "Consider setting a timeout value over 30 seconds.");
        }

        SamplerMode mode = arguments.boolFlag("alloc") ? SamplerMode.ALLOCATION : SamplerMode.EXECUTION;
        boolean allocLiveOnly = arguments.boolFlag("alloc-live-only");

        double interval = arguments.doubleFlag("interval");
        if (interval <= 0) {
            interval = mode.defaultInterval();
        }

        boolean ignoreSleeping = arguments.boolFlag("ignore-sleeping");
        boolean ignoreNative = arguments.boolFlag("ignore-native");
        boolean forceJavaSampler = arguments.boolFlag("force-java-sampler");

        Set<String> threads = arguments.stringFlag("thread");
        ThreadDumper threadDumper;
        if (threads.isEmpty()) {
            // use the server thread
            threadDumper = platform.getPlugin().getDefaultThreadDumper();
        } else if (threads.contains("*")) {
            threadDumper = ThreadDumper.ALL;
        } else {
            if (arguments.boolFlag("regex")) {
                threadDumper = new ThreadDumper.Regex(threads);
            } else {
                // specific matches
                threadDumper = new ThreadDumper.Specific(threads);
            }
        }

        ThreadGrouper threadGrouper;
        if (arguments.boolFlag("combine-all")) {
            threadGrouper = ThreadGrouper.AS_ONE;
        } else if (arguments.boolFlag("not-combined")) {
            threadGrouper = ThreadGrouper.BY_NAME;
        } else {
            threadGrouper = ThreadGrouper.BY_POOL;
        }

        int ticksOver = arguments.intFlag("only-ticks-over");
        TickHook tickHook = null;
        if (ticksOver != -1) {
            tickHook = platform.getTickHook();
            if (tickHook == null) {
                resp.replyPrefixed("[red]Tick counting is not supported!");
                return;
            }
        }

        resp.broadcastPrefixed("Starting a new profiler, please wait...");

        SamplerBuilder builder = new SamplerBuilder();
        builder.mode(mode);
        builder.threadDumper(threadDumper);
        builder.threadGrouper(threadGrouper);
        if (timeoutSeconds != -1) {
            builder.completeAfter(timeoutSeconds, TimeUnit.SECONDS);
        }
        builder.samplingInterval(interval);
        builder.ignoreSleeping(ignoreSleeping);
        builder.ignoreNative(ignoreNative);
        builder.forceJavaSampler(forceJavaSampler);
        builder.allocLiveOnly(allocLiveOnly);
        if (ticksOver != -1) {
            builder.ticksOver(ticksOver, tickHook);
        }

        Sampler sampler;
        try {
            sampler = builder.start(platform);
        } catch (UnsupportedOperationException e) {
            resp.replyPrefixed("[red]" + e.getMessage());
            return;
        }

        platform.getSamplerContainer().setActiveSampler(sampler);

        resp.broadcastPrefixed("[gold]" + (mode == SamplerMode.ALLOCATION ? "Allocation Profiler" : "Profiler") + " is now running! [gray](" + (sampler instanceof AsyncSampler ? "async" : "built-in java") + ")");

        if (timeoutSeconds == -1) {
            resp.broadcastPrefixed("It will run in the background until it is stopped by an admin.");
            resp.broadcastPrefixed("To stop the profiler and upload the results, run:");
            resp.broadcastPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler stop"));
            resp.broadcastPrefixed("To view the profiler while it's running, run:");
            resp.broadcastPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler open"));
        } else {
            resp.broadcastPrefixed("The results will be automatically returned after the profiler has been running for " + FormatUtil.formatSeconds(timeoutSeconds) + ".");
        }

        CompletableFuture<Sampler> future = sampler.getFuture();

        // send message if profiling fails
        future.whenCompleteAsync((s, throwable) -> {
            if (throwable != null) {
                resp.broadcastPrefixed("[red]Profiler operation failed unexpectedly. Error: " + throwable.toString());
                throwable.printStackTrace();
            }
        });

        // set activeSampler to null when complete.
        sampler.getFuture().whenCompleteAsync((s, throwable) -> platform.getSamplerContainer().unsetActiveSampler(s));

        // await the result
        if (timeoutSeconds != -1) {
            Sampler.ExportProps exportProps = getExportProps(platform, resp, arguments);
            boolean saveToFile = arguments.boolFlag("save-to-file");
            future.thenAcceptAsync(s -> {
                resp.broadcastPrefixed("The active profiler has completed! Uploading results...");
                handleUpload(platform, resp, s, exportProps, saveToFile);
            });
        }
    }

    private void profilerInfo(SparkPlatform platform, CommandResponseHandler resp) {
        Sampler sampler = platform.getSamplerContainer().getActiveSampler();
        if (sampler == null) {
            resp.replyPrefixed("The profiler isn't running!");
            resp.replyPrefixed("To start a new one, run:");
            resp.replyPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler start"));
        } else {
            resp.replyPrefixed("[gold]Profiler is already running!");

            long runningTime = (System.currentTimeMillis() - sampler.getStartTime()) / 1000L;

            if (sampler.isRunningInBackground()) {
                resp.replyPrefixed("It was started [white]automatically[gray] when spark enabled and has been running in the background for " + FormatUtil.formatSeconds(runningTime) + ".");
            } else {
                resp.replyPrefixed("So far, it has profiled for " + FormatUtil.formatSeconds(runningTime) + ".");
            }

            resp.replyPrefixed("To view the profiler while it's running, run:");
            resp.replyPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler open"));

            long timeout = sampler.getAutoEndTime();
            if (timeout == -1) {
                resp.replyPrefixed("To stop the profiler and upload the results, run:");
                resp.replyPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler stop"));
            } else {
                long timeoutDiff = (timeout - System.currentTimeMillis()) / 1000L;
                resp.replyPrefixed("It is due to complete automatically and upload results in " + FormatUtil.formatSeconds(timeoutDiff) + ".");
            }

            resp.replyPrefixed("To cancel the profiler without uploading the results, run:");
            resp.replyPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler cancel"));
        }
    }

    private void profilerOpen(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        BytesocksClient bytesocksClient = platform.getBytesocksClient();
        if (bytesocksClient == null) {
            resp.replyPrefixed("[red]The live viewer is not supported.");
            return;
        }

        Sampler sampler = platform.getSamplerContainer().getActiveSampler();
        if (sampler == null) {
            resp.replyPrefixed("The profiler isn't running!");
            resp.replyPrefixed("To start a new one, run:");
            resp.replyPrefixed(cmdPrompt("/" + platform.getPlugin().getCommandName() + " profiler start"));
            return;
        }

        Sampler.ExportProps exportProps = getExportProps(platform, resp, arguments);
        handleOpen(platform, bytesocksClient, resp, sampler, exportProps);
    }

    private void profilerTrustViewer(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        Set<String> ids = arguments.stringFlag("id");
        if (ids.isEmpty()) {
            resp.replyPrefixed("Please provide a client id with '--id <client id>'.");
            return;
        }

        for (String id : ids) {
            boolean success = platform.getTrustedKeyStore().trustPendingKey(id);
            if (success) {
                Sampler sampler = platform.getSamplerContainer().getActiveSampler();
                if (sampler != null) {
                    for (ViewerSocket socket : sampler.getAttachedSockets()) {
                        socket.sendClientTrustedMessage(id);
                    }
                }
                resp.replyPrefixed("Client connected to the viewer using id '" + id + "' is now trusted.");
            } else {
                resp.replyPrefixed("Unable to find pending client with id '" + id + "'.");
            }
        }
    }

    private void profilerCancel(SparkPlatform platform, CommandResponseHandler resp) {
        Sampler sampler = platform.getSamplerContainer().getActiveSampler();
        if (sampler == null) {
            resp.replyPrefixed("There isn't an active profiler running.");
        } else {
            platform.getSamplerContainer().stopActiveSampler(true);
            resp.broadcastPrefixed("[gold]Profiler has been cancelled.");
        }
    }

    private void profilerStop(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        Sampler sampler = platform.getSamplerContainer().getActiveSampler();

        if (sampler == null) {
            resp.replyPrefixed("There isn't an active profiler running.");
        } else {
            platform.getSamplerContainer().unsetActiveSampler(sampler);
            sampler.stop(false);

            boolean saveToFile = arguments.boolFlag("save-to-file");
            if (saveToFile) {
                resp.broadcastPrefixed("Stopping the profiler & saving results, please wait...");
            } else {
                resp.broadcastPrefixed("Stopping the profiler & uploading results, please wait...");
            }

            Sampler.ExportProps exportProps = getExportProps(platform, resp, arguments);
            handleUpload(platform, resp, sampler, exportProps, saveToFile);

            // if the previous sampler was running in the background, create a new one
            if (platform.getBackgroundSamplerManager().restartBackgroundSampler()) {
                resp.broadcastPrefixed("Restarted the background profiler. [gray](If you don't want this to happen, run: /" + platform.getPlugin().getCommandName() + " profiler cancel)");
            }
        }
    }

    private void handleUpload(SparkPlatform platform, CommandResponseHandler resp, Sampler sampler, Sampler.ExportProps exportProps, boolean saveToFileFlag) {
        SparkSamplerProtos.SamplerData output = sampler.toProto(platform, exportProps);

        boolean saveToFile = false;
        if (saveToFileFlag) {
            saveToFile = true;
        } else {
            try {
                String key = platform.getBytebinClient().postContent(output, MediaTypes.SPARK_SAMPLER_MEDIA_TYPE).key();
                String url = platform.getViewerUrl() + key;

                resp.broadcastPrefixed("[gold]Profiler stopped & upload complete!");
                resp.broadcast("[gray]" + url);

                platform.getActivityLog().addToLog(Activity.urlActivity(System.currentTimeMillis(), "Profiler", url));
            } catch (Exception e) {
                resp.broadcastPrefixed("[red]An error occurred whilst uploading the results. Attempting to save to disk instead.");
                e.printStackTrace();
                saveToFile = true;
            }
        }

        if (saveToFile) {
            Path file = platform.resolveSaveFile("profile", "sparkprofile");
            try {
                Files.write(file, output.toByteArray());

                resp.broadcastPrefixed("[gold]Profiler stopped & save complete!");
                resp.broadcastPrefixed("Data has been written to: " + file);
                resp.broadcastPrefixed("[gray]You can view the profile file using the web app @ " + platform.getViewerUrl());

                platform.getActivityLog().addToLog(Activity.fileActivity(System.currentTimeMillis(), "Profiler", file.toString()));
            } catch (IOException e) {
                resp.broadcastPrefixed("[red]An error occurred whilst saving the data.");
                e.printStackTrace();
            }
        }
    }

    private void handleOpen(SparkPlatform platform, BytesocksClient bytesocksClient, CommandResponseHandler resp, Sampler sampler, Sampler.ExportProps exportProps) {
        try {
            ViewerSocket socket = new ViewerSocket(platform, bytesocksClient, exportProps);
            sampler.attachSocket(socket);
            exportProps.channelInfo(socket.getPayload());

            SparkSamplerProtos.SamplerData data = sampler.toProto(platform, exportProps);

            String key = platform.getBytebinClient().postContent(data, MediaTypes.SPARK_SAMPLER_MEDIA_TYPE, "live").key();
            String url = platform.getViewerUrl() + key;

            resp.broadcastPrefixed("[gold]Profiler live viewer:");
            resp.broadcast("[gray]" + url);

            platform.getActivityLog().addToLog(Activity.urlActivity(System.currentTimeMillis(), "Profiler (live)", url));
        } catch (Exception e) {
            resp.replyPrefixed("[red]An error occurred whilst opening the live profiler.");
            e.printStackTrace();
        }
    }

    private Sampler.ExportProps getExportProps(SparkPlatform platform, CommandResponseHandler resp, Arguments arguments) {
        Sampler.ExportProps exportProps = new Sampler.ExportProps()
                .comment(Iterables.getFirst(arguments.stringFlag("comment"), null))
                .mergeMode(() -> {
                    MethodDisambiguator methodDisambiguator = new MethodDisambiguator();
                    return arguments.boolFlag("separate-parent-calls")
                            ? MergeMode.separateParentCalls(methodDisambiguator)
                            : MergeMode.sameMethod(methodDisambiguator);
                })
                .classSourceLookup(() -> ClassSourceLookup.create(platform));
        if (resp.sender() == null){
            exportProps.creator(new Data("mindustry server", null));
        } else {
            exportProps.creator(new Data(resp.sender().name(), resp.sender().uuid()));
        }
        return exportProps;
    }

    private static String cmdPrompt(String cmd) {
        return "  [white]" + cmd;
    }
}
