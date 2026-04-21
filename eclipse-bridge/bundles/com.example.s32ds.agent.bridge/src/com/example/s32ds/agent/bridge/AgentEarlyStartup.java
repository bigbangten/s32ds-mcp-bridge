package com.example.s32ds.agent.bridge;

import org.eclipse.ui.IStartup;

public final class AgentEarlyStartup implements IStartup {
    @Override
    public void earlyStartup() {
        BridgeServer.getInstance().startAsync();
    }
}
