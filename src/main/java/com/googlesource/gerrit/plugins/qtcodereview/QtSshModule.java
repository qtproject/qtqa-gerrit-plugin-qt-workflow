//
// Copyright (C) 2019-25 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.sshd.PluginCommandModule;
import com.google.inject.Inject;

class QtSshModule extends PluginCommandModule {
  @Inject
  QtSshModule(@PluginName String pluginName) {
    super(pluginName);
  }

  @Override
  protected void configureCommands() {
    command(QtCommandPing.class);
    command(QtCommandAdminChangeStatus.class);
    command(QtCommandBuildApprove.class);
    command(QtCommandNewBuild.class);
    command(QtCommandListStaging.class);
    command(QtCommandRebuildStaging.class);
    command(QtCommandStage.class);
    command(QtCommandUpdateCiStatus.class);
  }
}
