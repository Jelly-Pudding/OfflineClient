package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.rig.core.Rig;
import net.minecraft.network.protocol.Packet;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

// Counts the packets the game sends by kind and by the module whose code sent them. It listens
// after every module has had its say and a packet a module held back is not counted.
final class PacketTally {

    private static final String CLIENT = "com.jellypudding.offlineclient.";
    private static final List<String> MODULE_CODE = List.of(CLIENT + "modules.", CLIENT + "module.");
    private static final List<String> NOT_A_SENDER = List.of(CLIENT + "event.", CLIENT + "mixin.", CLIENT + "rig.");

    private final Predicate<Packet<?>> counted;
    private final Map<String, Integer> sent = new TreeMap<>();

    PacketTally(Predicate<Packet<?>> counted) {
        this.counted = counted;
    }

    void listen(Rig rig) {
        rig.act(mc -> OfflineClient.INSTANCE.getEventBus().register(this));
    }

    void stop(Rig rig) {
        rig.act(mc -> OfflineClient.INSTANCE.getEventBus().unregister(this));
    }

    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !counted.test(event.getPacket())) {
            return;
        }
        String kind = event.getPacket().getClass().getSimpleName().replace("Serverbound", "").replace("Packet", "");
        synchronized (sent) {
            sent.merge(kind + " from " + sender(), 1, Integer::sum);
        }
    }

    // What was counted since the last call. Each line names a kind and its sender.
    Map<String, Integer> drain() {
        synchronized (sent) {
            Map<String, Integer> counted = new TreeMap<>(sent);
            sent.clear();
            return counted;
        }
    }

    // The lines with the highest counts first.
    static List<String> busiest(Map<String, Integer> counted, int most) {
        return counted.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(most).map(line -> line.getValue() + " " + line.getKey()).toList();
    }

    // The module on the stack or failing that the nearest class of the client. A packet the
    // game sends by itself has neither.
    private static String sender() {
        List<String> classes = StackWalker.getInstance().walk(frames -> frames
            .map(StackWalker.StackFrame::getClassName).filter(name -> name.startsWith(CLIENT)
                && NOT_A_SENDER.stream().noneMatch(name::startsWith)).toList());
        return classes.stream().filter(name -> MODULE_CODE.stream().anyMatch(name::startsWith)).findFirst()
            .or(() -> classes.stream().findFirst()).map(PacketTally::simple).orElse("the game");
    }

    private static String simple(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        int inner = name.indexOf('$');
        return inner < 0 ? name : name.substring(0, inner);
    }
}
