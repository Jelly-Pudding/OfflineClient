package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// Works out what a blast on the block you point at would do.
public final class DamageCommand extends Command {

    // How far away another player still counts for the report.
    private static final double TARGET_RANGE = 16;

    public DamageCommand() {
        super("damage", "Works out blast damage on the block you point at.",
            "damage [crystal|anchor|bed]", "dmg");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length > 1) {
            usage();
            return;
        }
        float power = ExplosionUtil.CRYSTAL_POWER;
        String kind = "crystal";
        if (args.length == 1) {
            kind = args[0].toLowerCase(Locale.ROOT);
            switch (kind) {
                case "crystal" -> power = ExplosionUtil.CRYSTAL_POWER;
                case "anchor", "bed" -> power = ExplosionUtil.RESPAWN_BLOCK_POWER;
                default -> {
                    usage();
                    return;
                }
            }
        }
        if (!(OfflineClient.MC.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) {
            ChatUtil.error("Point at a block first.");
            return;
        }
        BlockPos pos = hit.getBlockPos().above();
        Vec3 source = kind.equals("crystal")
            ? new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) : Vec3.atCenterOf(pos);
        ChatUtil.message("§7You would take §b" + hearts(
            ExplosionUtil.blastDamage(player, source, power)));
        Player target = EntityUtil.nearestEnemy(TARGET_RANGE);
        if (target == null) {
            ChatUtil.message("§7Nobody else is close enough to work out.");
            return;
        }
        ChatUtil.message("§b" + EntityUtil.nameOf(target) + " §7would take §b"
            + hearts(ExplosionUtil.blastDamage(target, source, power)));
    }

    private static String hearts(float damage) {
        return String.format("%.1f", damage) + " hearts";
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1
            ? CommandManager.filter(current, List.of("crystal", "anchor", "bed")) : List.of();
    }
}
