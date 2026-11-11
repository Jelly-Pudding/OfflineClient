package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.ChoiceListSetting;

// What a condition is built into. The pick list its rows hang under and the tail every row
// name ends in such as the number of a rule. A module with one list uses no tail.
record Frame(ChoiceListSetting list, String tail) {

    // The name of a row such as Health 3 for the health of rule three.
    String name(String words) {
        return words + tail;
    }
}
