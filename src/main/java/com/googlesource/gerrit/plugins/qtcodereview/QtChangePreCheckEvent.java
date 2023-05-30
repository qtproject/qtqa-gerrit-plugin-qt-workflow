//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.common.InputWithMessage;
import com.google.gerrit.server.events.ChangeEvent;
import com.googlesource.gerrit.plugins.qtcodereview.QtPrecheckMessage;

public class QtChangePreCheckEvent extends ChangeEvent {
  public static final String TYPE = "precheck";
  public String commitID = "";
  public String precheckType = "";
  public String platforms = "";
  public boolean onlybuild = false;
  public boolean cherrypick = false;
  public int patchSet = 0;

  public QtChangePreCheckEvent(Change change, PatchSet patchSet, QtPrecheckMessage message) {
    super(TYPE, change);
    this.patchSet = patchSet.id().get();
    this.onlybuild = message.onlybuild;
    this.cherrypick = message.cherrypick;
    this.precheckType = message.type;
    this.platforms = message.platforms;
  }
}
