package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.ChoiceListSetting;

// A module with one list uses no tail.
record Frame(ChoiceListSetting list, String tail) {

    String name(String words) {
        return words + tail;
    }
}
