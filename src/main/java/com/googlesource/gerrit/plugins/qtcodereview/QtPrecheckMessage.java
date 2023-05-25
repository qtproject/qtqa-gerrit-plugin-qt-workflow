//
// Copyright (C) 2023 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

public class QtPrecheckMessage {
  public boolean onlyBuild;
  public boolean cherrypick;
  public String type;
  public String platforms;

  public QtPrecheckMessage(boolean onlyBuild, boolean cherrypick, String type, String platforms) {
    this.onlyBuild = onlyBuild;
    this.cherrypick = cherrypick;
    this.type = type;
    this.platforms = platforms;
  }
}
