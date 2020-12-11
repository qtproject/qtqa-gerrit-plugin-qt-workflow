// Copyright (C) 2021 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.entities.AccountGroup;
import org.apache.http.HttpStatus;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")

@UseSsh
public class QtPreCheckIT extends QtCodeReviewIT {

  @Test
  public void preCheck_Ok() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    projectOperations.project(project).forUpdate()
        .add(allowLabel("Code-Review").ref("refs/heads/*").group(registered).range(-2, 2))
        .update();

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertOK();
  }

  @Test
  public void errorPreCheck_No_Permission() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    projectOperations.project(project).forUpdate()
        .add(allowLabel("Code-Review").ref("refs/heads/*").group(registered).range(-1, 1))
        .update();

    RestResponse response = call_REST_API_PreCheck(c.getChangeId(), c.getCommit().getName());
    response.assertStatus(HttpStatus.SC_FORBIDDEN);
  }

  protected RestResponse call_REST_API_PreCheck(String changeId, String revisionId)
      throws Exception {
    String url =
        "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~precheck";
    RestResponse response = userRestSession.post(url);
    return response;
  }

}
