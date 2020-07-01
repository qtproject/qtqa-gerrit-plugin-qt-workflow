//
// Copyright (C) 2020 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.Supplier;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.events.PatchSetEvent;

public class QtChangeIntegrationFailEvent extends PatchSetEvent {
  public static final String TYPE = "change-integration-fail";
  public Supplier<AccountAttribute> submitter;
  public String newRev;

  public QtChangeIntegrationFailEvent(Change change) {
    super(TYPE, change);
  }
}
