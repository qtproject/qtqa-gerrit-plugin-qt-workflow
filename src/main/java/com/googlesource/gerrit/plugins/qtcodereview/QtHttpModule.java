//
// Copyright (C) 2023 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.inject.servlet.ServletModule;

public class QtHttpModule extends ServletModule {
  @Override
  protected void configureServlets() {
    DynamicSet.bind(binder(), WebUiPlugin.class)
        .toInstance(new JavaScriptPlugin("qt-workflow-ui.js"));
  }
}
