//
// Copyright (C) 2023-24 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.client.MenuItem;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.extensions.webui.TopMenu.MenuEntry;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.CurrentUser;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.lib.Config;

@Singleton
public class QtCiStatusTopMenu implements TopMenu {
  private static final String KEY_CI_MENU_ENABLED = "ciMenuEnabled";
  private final String pluginName;
  private final Provider<CurrentUser> userProvider;
  private final List<MenuEntry> menuEntries;
  private final List<MenuEntry> menuEntriesEmpty = new ArrayList<TopMenu.MenuEntry>();
  private final boolean ciMenuEnabled;

  @Inject
  public QtCiStatusTopMenu(PluginConfigFactory cfgFactory, @PluginName String pluginName, Provider<CurrentUser> userProvider) {
    this.pluginName = pluginName;
    this.userProvider = userProvider;
    menuEntries = new ArrayList<TopMenu.MenuEntry>();

    Config cfg = cfgFactory.getGlobalPluginConfig(pluginName);
    this.ciMenuEnabled = cfg.getBoolean(pluginName, null, KEY_CI_MENU_ENABLED, false);

    if (this.ciMenuEnabled) {
      // Place holder menu, the list content is replaced by the UI plugin.
      menuEntries.add(
          new MenuEntry(
              "CI-Status",
              Collections.singletonList(
                  new MenuItem("Workload", "#no_op_url", "no_op_target", "no_op_id"))));
    }
  }

  @Override
  public List<MenuEntry> getEntries() {
    if (userProvider.get().isIdentifiedUser()) return menuEntries;
    return menuEntriesEmpty;
  }
}
