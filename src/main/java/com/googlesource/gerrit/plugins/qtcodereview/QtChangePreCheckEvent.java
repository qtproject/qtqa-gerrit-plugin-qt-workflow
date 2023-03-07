//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.common.InputWithMessage;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.qtcodereview.QtPrecheckMessage;

public class QtChangePreCheckEvent extends PatchSetEvent {
  public static final String TYPE = "precheck";
  public String commitID = "";
  public String precheckType = "";
  public String platforms = "";
  public boolean onlyBuild = false;

  public QtChangePreCheckEvent(Change change, QtPrecheckMessage message) {
    super(TYPE, change);
    this.onlyBuild = message.onlyBuild;
    this.precheckType = message.type;
    this.platforms = message.platforms;
  }
}
