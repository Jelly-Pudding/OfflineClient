package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// A command that is sent to a place. The place is three coordinates or two across the
// ground or the name of a player or a waypoint or a kind of entity. With nothing typed
// it is the block pointed at.
public abstract class PlaceCommand extends Command {

    // How a place reads in a usage line.
    protected static final String PLACE = "[x y z|x z|name]";

    protected PlaceCommand(String name, String description, String usage, String... aliases) {
        super(name, description, usage, aliases);
    }

    // Null after telling the player what was wrong.
    protected Travel.Stop place(String[] words, LocalPlayer player) {
        Vec3 here = Vec3.atLowerCornerOf(player.blockPosition());
        return switch (words.length) {
            case 0 -> pointedAt();
            case 1 -> named(words[0]);
            case 2 -> across(flatCoordinates(words, 0, here));
            case 3 -> spot(coordinates(words, 0, here));
            default -> {
                ChatUtil.error("A place is three coordinates or two across the ground or a name.");
                yield null;
            }
        };
    }

    private static Travel.Stop spot(Vec3 typed) {
        return typed == null ? null : Travel.Stop.at(BlockPos.containing(typed));
    }

    private static Travel.Stop across(Vec3 typed) {
        return typed == null ? null : Travel.Stop.column(BlockPos.containing(typed));
    }

    private static Travel.Stop pointedAt() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit != null) {
            return Travel.Stop.at(hit.getBlockPos().above());
        }
        ChatUtil.error("Point at a block or type the coordinates.");
        return null;
    }

    private static Travel.Stop named(String name) {
        Travel.Stop stop = known(name);
        if (stop == null) {
            ChatUtil.error("No player or waypoint or kind of entity nearby is called " + name + ".");
        }
        return stop;
    }

    // The place a name stands for or null when nothing goes by it. A player in sight goes
    // before a waypoint of the same name and a waypoint before a kind of entity. The place
    // is the spot they stand on as the command is typed.
    protected static Travel.Stop known(String name) {
        Player other = EntityUtil.otherPlayerNamed(name);
        if (other != null) {
            return Travel.Stop.at(other.blockPosition());
        }
        WaypointStore.Waypoint waypoint = WaypointStore.get().find(name);
        if (waypoint != null) {
            return Travel.Stop.at(waypoint.pos());
        }
        Entity nearest = nearestOfKind(name);
        return nearest == null ? null : Travel.Stop.at(nearest.blockPosition());
    }

    private static Entity nearestOfKind(String name) {
        EntityType<?> kind = CommandManager.registryEntry(BuiltInRegistries.ENTITY_TYPE, name);
        return kind == null ? null
            : EntityUtil.nearest(Double.MAX_VALUE, entity -> entity.getType() == kind && entity.isAlive());
    }

    private static List<Player> players() {
        List<Player> others = new ArrayList<>();
        for (Player other : OfflineClient.MC.level.players()) {
            if (EntityUtil.isOtherPlayer(other)) {
                others.add(other);
            }
        }
        return others;
    }

    // What may come next in a place. The word counts from nought within it.
    protected static List<String> placeOptions(int word) {
        LocalPlayer player = OfflineClient.MC.player;
        List<String> options = new ArrayList<>();
        if (player == null || word > 2) {
            return options;
        }
        BlockPos pos = player.blockPosition();
        if (word == 0) {
            players().forEach(other -> options.add(EntityUtil.nameOf(other)));
            WaypointStore.get().here().forEach(waypoint -> options.add(waypoint.name()));
        }
        options.add("~");
        options.add(String.valueOf(word == 0 ? pos.getX() : word == 1 ? pos.getY() : pos.getZ()));
        return options;
    }
}
