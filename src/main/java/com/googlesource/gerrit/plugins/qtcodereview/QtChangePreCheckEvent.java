//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.common.InputWithMessage;
import com.google.gerrit.server.events.PatchSetEvent;

public class QtChangePreCheckEvent extends PatchSetEvent {
  public static final String TYPE = "precheck";
  public String commitID = "";
  public String precheckType = "";
  public String platforms = "";
  public boolean onlyBuild = false;

  public QtChangePreCheckEvent(Change change, InputWithMessage data) {
    super(TYPE, change);
    String[] inputs = data.message.split("&");

    for (String input : inputs) {
      String[] values = input.split(":");
      String property = values[0];
      if (values.length < 2) {
        continue;
      }

      if (property.equals("buildonly")) {
        this.onlyBuild = Boolean.parseBoolean(values[1]);
      } else if (property.equals("type")) {
        this.precheckType = values[1];
      } else if (property.equals("platforms") && values.length > 1) {
        this.platforms = values[1];
      }
    }
  }
}
