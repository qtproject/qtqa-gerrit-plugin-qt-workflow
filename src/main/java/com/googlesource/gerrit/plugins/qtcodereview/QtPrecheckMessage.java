//
// Copyright (C) 2023 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

public class QtPrecheckMessage {
  public boolean onlybuild;
  public boolean cherrypick;
  public String type;
  public String platforms;

  public QtPrecheckMessage(boolean onlybuild, boolean cherrypick, String type, String platforms) {
    this.onlybuild = onlybuild;
    this.cherrypick = cherrypick;
    this.type = type;
    this.platforms = platforms;
  }
}
