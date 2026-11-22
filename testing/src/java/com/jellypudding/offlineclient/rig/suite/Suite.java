package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;

public interface Suite {

    String name();

    void run(Rig rig) throws Exception;
}
