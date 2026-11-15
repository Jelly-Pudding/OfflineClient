package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// The values a setting can be put through. A switch is flipped and a choice takes each of its
// options and a number goes to both ends of its slider and a colour moves round the wheel.
// Lists and key binds and buttons are left as they are.
final class Tweaks {

    private static final float HUE_STEP = 120;
    private static final float FULL_TURN = 360;
    private static final String ADDED_WORDS = " rig";

    // One value to give a setting. It says what did not take or null when the value took.
    record Tweak(String label, Supplier<String> apply) {
    }

    private Tweaks() {
    }

    // Every value worth trying. Only the switches and the choices when numbers and colours are
    // to be left alone.
    static List<Tweak> of(Setting<?> setting, boolean numbersToo) {
        List<Tweak> tweaks = new ArrayList<>();
        switch (setting) {
            case BoolSetting flag -> tweaks.add(flip(flag));
            case EnumSetting<?> choice -> tweaks.addAll(options(choice));
            case NumberSetting number when numbersToo -> {
                tweaks.add(new Tweak(number.getName() + " at its least", () -> set(number, number.getSliderMin())));
                tweaks.add(new Tweak(number.getName() + " at its most", () -> set(number, number.getSliderMax())));
            }
            case ColorSetting colour when numbersToo ->
                tweaks.add(new Tweak(colour.getName() + " turned", () -> turn(colour)));
            default -> {
            }
        }
        return tweaks;
    }

    // One change that leaves the setting at something other than its default. Null for a kind
    // of setting this class leaves alone.
    static Tweak change(Setting<?> setting) {
        return switch (setting) {
            case BoolSetting flag -> flip(flag);
            case EnumSetting<?> choice -> new Tweak(choice.getName() + " moved on", () -> {
                choice.cycle(true);
                return null;
            });
            case NumberSetting number -> new Tweak(number.getName() + " moved", () -> set(number,
                number.getValue() == number.getSliderMin() ? number.getSliderMax() : number.getSliderMin()));
            case ColorSetting colour -> new Tweak(colour.getName() + " turned", () -> turn(colour));
            case TextSetting text when !text.isSecret() -> new Tweak(text.getName() + " written", () -> {
                text.setValue(text.getValue() + ADDED_WORDS);
                return null;
            });
            default -> null;
        };
    }

    private static Tweak flip(BoolSetting flag) {
        return new Tweak(flag.getName() + " flipped", () -> {
            boolean wanted = !flag.isOn();
            flag.setValue(wanted);
            return flag.isOn() == wanted ? null : flag.getName() + " did not flip";
        });
    }

    private static <E extends Enum<E>> List<Tweak> options(EnumSetting<E> choice) {
        List<Tweak> tweaks = new ArrayList<>();
        for (E option : choice.getValue().getDeclaringClass().getEnumConstants()) {
            tweaks.add(new Tweak(choice.getName() + " as " + option, () -> {
                choice.setValue(option);
                return choice.getValue() == option ? null : choice.getName() + " did not take " + option;
            }));
        }
        return tweaks;
    }

    private static String set(NumberSetting number, double value) {
        number.setValue(value);
        return null;
    }

    private static String turn(ColorSetting colour) {
        colour.setHue((colour.getHue() + HUE_STEP) % FULL_TURN);
        return null;
    }
}
