// Copyright (C) 2023-24 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.permissions.DefaultPermissionMappings.pluginCapabilityName;

import com.google.gerrit.acceptance.config.GlobalPluginConfig;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.extensions.webui.TopMenu.MenuEntry;
import com.google.inject.Inject;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCiStatusIT extends QtCodeReviewIT {

  private static final String pools_empty_json = "\"[ ]\"";

  private static final String pools_single_json =
      "\"[ { name: \\\"pool 1\\\", running: 123, queue: 234, load: 0 } ]\"";

  private static final String pools_1st_json =
      "\"[ { name: \\\"pool 1\\\", running: 1111, queue: 2222, load: 0 },"
          + " { name: \\\"pool 2\\\", running: 3333, queue: 4444, load: 1 },"
          + " { name: \\\"pool 3\\\", running: 5555, queue: 6666, load: 1 }"
          + " ]\"";

  private static final String pools_2nd_json =
      "\"[ { name: \\\"pool 1\\\", running: 4321, queue: 5432, load: 0 },"
          + " { name: \\\"pool 2\\\", running: 6543, queue: 7654, load: 1 },"
          + " { name: \\\"pool 3\\\", running: 8765, queue: 9876, load: 2 }"
          + " ]\"";

  @Before
  public void SetPermissions() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(
            allowCapability(
                    pluginCapabilityName(
                        new PluginPermission(
                            "gerrit-plugin-qt-workflow",
                            QtCiStatusUpdateCapability.CI_STATUS_UPDATE)))
                .group(REGISTERED_USERS))
        .update();
  }

  @Test
  public void CiStatus_BeforeUpdate() throws Exception {
    RestResponse response = qtCiStatusGet();
    String html = response.getEntityContent();
    assertThat(html).contains("\"status_color\":\"var(--disabled-foreground)\"");
    assertThat(html).doesNotContain("running");
  }

  @Test
  public void CiStatusUpdate_EmptyPool() throws Exception {
    String result = qtCiStatusUpdate(pools_empty_json, false);
    RestResponse response = qtCiStatusGet();
    response.assertOK();
    String html = response.getEntityContent();
    assertThat(html).contains("\"status_color\":\"var(--disabled-foreground)\"");
    assertThat(html).doesNotContain("running");
  }

  @Test
  public void CiStatusUpdate_SinglePool() throws Exception {
    String result = qtCiStatusUpdate(pools_single_json, false);
    RestResponse response = qtCiStatusGet();
    response.assertOK();
    String html = response.getEntityContent();
    assertThat(html).contains("\"status_color\":\"var(--success-foreground)\"");
    assertThat(html).contains("pool 1");
    assertThat(html).contains("123");
    assertThat(html).contains("234");
  }

  @Test
  public void CiStatusUpdate_MultipleTimes() throws Exception {
    // 1st update
    String result = qtCiStatusUpdate(pools_1st_json, false);
    RestResponse response = qtCiStatusGet();
    response.assertOK();
    String html = response.getEntityContent();
    assertThat(html).contains("\"status_color\":\"var(--warning-foreground)\"");
    assertThat(html).contains("pool 1");
    assertThat(html).contains("1111");
    assertThat(html).contains("2222");
    assertThat(html).contains("pool 2");
    assertThat(html).contains("3333");
    assertThat(html).contains("4444");
    assertThat(html).contains("pool 3");
    assertThat(html).contains("5555");
    assertThat(html).contains("6666");

    // 2nd update
    result = qtCiStatusUpdate(pools_2nd_json, false);
    response = qtCiStatusGet();
    response.assertOK();
    html = response.getEntityContent();
    assertThat(html).contains("\"status_color\":\"var(--error-foreground)\"");
    assertThat(html).contains("pool 1");
    assertThat(html).contains("4321");
    assertThat(html).contains("5432");
    assertThat(html).contains("pool 2");
    assertThat(html).contains("6543");
    assertThat(html).contains("7654");
    assertThat(html).contains("pool 3");
    assertThat(html).contains("8765");
    assertThat(html).contains("9876");
  }

  @Test
  public void topMenuHidden() throws Exception {
    List<MenuEntry> topMenuItems = gApi.config().server().topMenus();
    assertThat(topMenuItems).isEmpty();
  }

  @Test
  @UseLocalDisk
  @GlobalPluginConfig(pluginName = "gerrit-plugin-qt-workflow", name = "gerrit-plugin-qt-workflow.ciMenuEnabled",
    value = "true")
  public void topMenuVisible() throws Exception {
    List<MenuEntry> topMenuItems = gApi.config().server().topMenus();
    assertThat(topMenuItems.get(0).name).isEqualTo("CI-Status");
  }

  @Test
  public void error_InvalidJSON() throws Exception {
    String result = qtCiStatusUpdate("invalidjsonformat", true);
    assertThat(result).contains("JsonSyntaxException");
  }

  @Test
  public void error_EmptyParameter() throws Exception {
    String result = qtCiStatusUpdate("", true);
    assertThat(result).contains("Option \"--queueinfo (-i)\" takes an operand");
  }

  @Test
  public void error_NoParameter() throws Exception {
    String command;
    command = "gerrit-plugin-qt-workflow ci-status-update";
    String result = userSshSession.exec(command);
    assertThat(userSshSession.getError()).contains("Option \"--queueinfo (-i)\" is required");
  }

  @Test
  public void error_NoPermission() throws Exception {
    projectOperations.allProjectsForUpdate().removeAllAccessSections().update();
    String result = qtCiStatusUpdate(pools_single_json, true);
    assertThat(result).contains("not permitted");
  }

  private String qtCiStatusUpdate(String json, Boolean expectFail) throws Exception {
    String command;
    command = "gerrit-plugin-qt-workflow ci-status-update";
    command += " -i " + json;
    String result = userSshSession.exec(command);
    if (expectFail) assertThat(userSshSession.getError()).isNotNull();
    else assertThat(userSshSession.getError()).isNull();
    return userSshSession.getError();
  }

  private RestResponse qtCiStatusGet() throws Exception {
    return userRestSession.get("/accounts/self/gerrit-plugin-qt-workflow~cistatus");
  }
}
