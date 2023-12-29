// Copyright (C) 2024 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.config.CapabilityDefinition;

public class QtCiStatusUpdateCapability extends CapabilityDefinition {
  static final String CI_STATUS_UPDATE = "ciStatusUpdate";

  @Override
  public String getDescription() {
    return "Update CI status to Gerrit via custom API";
  }
}
