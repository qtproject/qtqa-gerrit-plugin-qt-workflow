// Copyright (C) 2021-24 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.config.GlobalPluginConfig;
import com.google.gerrit.acceptance.testsuite.group.GroupOperations;
import com.google.gerrit.entities.AccountGroup;
import com.google.inject.Inject;
import org.apache.http.HttpStatus;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtPreCheckIT extends QtCodeReviewIT {

  @Inject private GroupOperations groupOperations;

  static final QtPrecheckMessage inputDefault = new QtPrecheckMessage(false, false, "default", "");
  static final QtPrecheckMessage inputFull = new QtPrecheckMessage(true, false, "full", "");
  static final QtPrecheckMessage inputCustom =
      new QtPrecheckMessage(false, true, "custom", "os:linux and arch: arm64");

  @Test
  public void preCheck_Ok() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 2);

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertOK();
  }

  @Test
  @UseLocalDisk
  @GlobalPluginConfig(
      pluginName = "gerrit-plugin-qt-workflow",
      name = "precheck.disabled.projects",
      values = {"qt/qt5", "qt/test-qt5"})
  public void errorPreCheck_OK_Allowed() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 2);

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertOK();
  }

  @Test
  @UseLocalDisk
  @GlobalPluginConfig(
      pluginName = "gerrit-plugin-qt-workflow",
      name = "precheck.allowed.groups",
      values = {"Allow Prechecks", "non-existing-group"})
  public void errorPreCheck_OK_Allowed_For_Group() throws Exception {
    AccountGroup.UUID group = groupOperations.newGroup().name("Allow Prechecks").create();
    gApi.groups().id(group.get()).addMembers(user.username());

    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 1);

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertOK();
  }

  @Test
  @UseLocalDisk
  @GlobalPluginConfig(
      pluginName = "gerrit-plugin-qt-workflow",
      name = "precheck.disabled.projects",
      values = {
        "qt/qt5",
        "com.googlesource.gerrit.plugins.qtcodereview.QtPreCheckIT_errorPreCheck_Not_Allowed_project"
      })
  public void errorPreCheck_Not_Allowed() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 2);

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertStatus(HttpStatus.SC_FORBIDDEN);
  }

  @Test
  public void errorPreCheck_No_Permission() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 1);

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertStatus(HttpStatus.SC_FORBIDDEN);
  }

  @Test
  public void preCheck_Ok_TestInputs() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    setUserReviewPermission(REGISTERED_USERS, 2);

    RestResponse response;
    response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName(), inputFull);
    response.assertOK();

    response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName(), inputCustom);
    response.assertOK();
  }

  protected RestResponse call_REST_API_PreCheck(String changeId, String revisionId)
      throws Exception {
    return call_REST_API_PreCheck(changeId, revisionId, inputDefault);
  }

  protected RestResponse call_REST_API_PreCheck(
      String changeId, String revisionId, QtPrecheckMessage input) throws Exception {
    String url =
        "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~precheck";
    RestResponse response = userRestSession.post(url, input);
    return response;
  }

  private void setUserReviewPermission(AccountGroup.UUID group, Integer maxValue) {
    AccountGroup.UUID registered = systemGroupBackend.getGroup(group).getUUID();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel("Code-Review")
                .ref("refs/heads/*")
                .group(registered)
                .range(-maxValue, maxValue))
        .update();
  }
}
