//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.sshd.PluginCommandModule;

class QtSshModule extends PluginCommandModule {

    @Override
    protected void configureCommands() {
        command(QtCommandPing.class);
        command(QtCommandNewBuild.class);
        command(QtCommandListStaging.class);
    }
}
