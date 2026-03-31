// Copyright (C) 2026 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.config.CapabilityDefinition;

public class QtStagingPromoteCapability extends CapabilityDefinition {
  static final String STAGING_PROMOTE = "stagingPromote";

  @Override
  public String getDescription() {
    return "Promote changes from prestaged (staging queue) to staged";
  }
}
