package com.jellypudding.offlineclient.rig;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Ledger;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.WindowPlace;
import com.jellypudding.offlineclient.rig.duo.Partner;
import com.jellypudding.offlineclient.rig.duo.PartnerLoop;
import com.jellypudding.offlineclient.rig.suite.BootSuite;
import com.jellypudding.offlineclient.rig.suite.BuildersSuite;
import com.jellypudding.offlineclient.rig.suite.CombatSuite;
import com.jellypudding.offlineclient.rig.suite.GotoSuite;
import com.jellypudding.offlineclient.rig.suite.GuardSuite;
import com.jellypudding.offlineclient.rig.suite.HuntingSuite;
import com.jellypudding.offlineclient.rig.suite.MiscSuite;
import com.jellypudding.offlineclient.rig.suite.MovementSuite;
import com.jellypudding.offlineclient.rig.suite.PlayerSuite;
import com.jellypudding.offlineclient.rig.suite.RenderSuite;
import com.jellypudding.offlineclient.rig.suite.ScenariosSuite;
import com.jellypudding.offlineclient.rig.suite.ScreensSuite;
import com.jellypudding.offlineclient.rig.suite.ServerSuite;
import com.jellypudding.offlineclient.rig.suite.SettingsSuite;
import com.jellypudding.offlineclient.rig.suite.SmokeSuite;
import com.jellypudding.offlineclient.rig.suite.StationsSuite;
import com.jellypudding.offlineclient.rig.suite.Suite;
import com.jellypudding.offlineclient.rig.suite.TogetherSuite;
import com.jellypudding.offlineclient.rig.suite.WorldSuite;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.sounds.SoundSource;

import java.util.List;

// The one test the game test framework starts. It runs every suite of the rig in turn and
// leaves the game on the title screen for the framework to close.
public final class RigMain implements FabricClientGameTest {

    // The size every picture is drawn at. The real window stays small.
    private static final int FRAME_WIDTH = 1920;
    private static final int FRAME_HEIGHT = 1080;
    private static final String WHOLE_SUITE = "the suite ran to its end";

    @Override
    public void runTest(ClientGameTestContext context) {
        Rig rig = new Rig(context, RigConfig.out(), RigConfig.filter());
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.act(mc -> {
            for (SoundSource source : SoundSource.values()) {
                mc.options.getSoundSourceOptionInstance(source).set(0.0);
            }
            WindowPlace.tuckAway(mc.getWindow().handle());
        });
        if (RigConfig.partner()) {
            new PartnerLoop(rig).run();
            return;
        }
        try {
            rig.frames().drawAt(FRAME_WIDTH, FRAME_HEIGHT);
            Ledger.write(rig);
            for (Suite suite : suites()) {
                run(rig, suite);
            }
        } finally {
            rig.closeWorld();
            Partner.end();
            rig.frames().drawAt(size[0], size[1]);
        }
    }

    private static List<Suite> suites() {
        return List.of(new BootSuite(), new SmokeSuite(), new SettingsSuite(), new ScreensSuite(),
            new ScenariosSuite(),
            new MovementSuite(), new CombatSuite(), new WorldSuite(), new PlayerSuite(), new MiscSuite(),
            new StationsSuite(), new BuildersSuite(), new HuntingSuite(), new TogetherSuite(), new RenderSuite(),
            new GotoSuite(),
            new ServerSuite(), new GuardSuite());
    }

    // A suite that breaks outside its cases is written down as one failed case and the next
    // suite still runs.
    private static void run(Rig rig, Suite suite) {
        Throwable broken = null;
        try {
            suite.run(rig);
        } catch (Throwable thrown) {
            broken = thrown;
        }
        try {
            rig.closeWorld();
        } catch (Throwable thrown) {
            broken = broken == null ? thrown : broken;
        }
        if (broken != null) {
            Throwable cause = broken;
            rig.run(suite.name(), Case.of(WHOLE_SUITE, each -> {
                throw new IllegalStateException("The suite broke outside its cases", cause);
            }));
        }
    }
}
