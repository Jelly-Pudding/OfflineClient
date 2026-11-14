package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;

// A group of cases that share a stage.
public interface Suite {

    String name();

    void run(Rig rig) throws Exception;
}
