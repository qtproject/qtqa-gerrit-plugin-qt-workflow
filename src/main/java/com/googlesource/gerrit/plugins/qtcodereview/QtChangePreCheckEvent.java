//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.Change;
import com.google.gerrit.server.events.PatchSetEvent;

public class QtChangePreCheckEvent extends PatchSetEvent {
  public static final String TYPE = "precheck";
  public String commitID = "";

  public QtChangePreCheckEvent(Change change) {
    super(TYPE, change);
  }
}
