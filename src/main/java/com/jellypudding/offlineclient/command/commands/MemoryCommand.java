package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.lang.management.ManagementFactory;

public final class MemoryCommand extends Command {

    private static final long MEGABYTE = 1024 * 1024;

    // Java started with this flag ignores every request to free memory.
    private static final String REQUESTS_IGNORED = "-XX:+DisableExplicitGC";

    public MemoryCommand() {
        super("memory", "Shows the memory the game uses and can free what it no longer needs.", "memory [free]",
            "ram");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            report();
        } else if (args.length == 1 && args[0].equalsIgnoreCase("free")) {
            free();
        } else {
            usage();
        }
    }

    private static void report() {
        Runtime runtime = Runtime.getRuntime();
        ChatUtil.row("Used", usedOfLimit(runtime));
        ChatUtil.row("Reserved", megabytes(runtime.totalMemory()));
    }

    // Measured before and after. The game pauses for as long as Java takes.
    private static void free() {
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains(REQUESTS_IGNORED)) {
            ChatUtil.error("Java was started with requests to free memory switched off.");
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long before = used(runtime);
        System.gc();
        long freed = before - used(runtime);
        if (freed < MEGABYTE) {
            ChatUtil.message("§7Nothing was freed. The game uses §b" + usedOfLimit(runtime) + "§7.");
            return;
        }
        ChatUtil.message("§7Freed §b" + megabytes(freed) + "§7. The game uses §b" + usedOfLimit(runtime) + "§7.");
    }

    private static String usedOfLimit(Runtime runtime) {
        return megabytes(used(runtime)) + " of " + megabytes(runtime.maxMemory());
    }

    private static long used(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String megabytes(long bytes) {
        return bytes / MEGABYTE + " MB";
    }
}
