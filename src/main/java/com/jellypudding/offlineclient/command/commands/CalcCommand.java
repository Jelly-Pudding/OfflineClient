package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.Calculator;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

// A live value in braces is filled in first. Half your x is {x}/2.
public final class CalcCommand extends Command {

    // Digits kept after the point.
    private static final int DECIMALS = 6;

    // Past this a number is written with a power of ten. Its digits would fill the chat.
    private static final double LONGEST_PLAIN = 1e15;

    public CalcCommand() {
        super("calc", "Works out a sum. A click on the answer copies it.", "calc <sum>", "calculate", "math");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String sum = LiveText.fill(words(args, 0));
        Calculator.Answer answer = Calculator.work(sum);
        if (answer.problem() != null) {
            ChatUtil.error(answer.problem());
            return;
        }
        String result = Math.abs(answer.value()) >= LONGEST_PLAIN ? String.valueOf(answer.value())
            : ChatUtil.decimal(answer.value(), DECIMALS);
        ChatUtil.component(ChatUtil.copyOnClick(
            Component.literal("§7" + sum + " §8= §b" + result + stacks(answer.value())), result));
    }

    // A whole count past one stack is also said in stacks such as 27 stacks and 3.
    private static String stacks(double value) {
        int stack = Item.DEFAULT_MAX_STACK_SIZE;
        if (value != Math.rint(value) || value <= stack || value > Integer.MAX_VALUE) {
            return "";
        }
        int whole = (int) value;
        int over = whole % stack;
        return " §8(" + Tally.counted(whole / stack, "stack") + (over == 0 ? "" : " and " + over) + ")";
    }
}
