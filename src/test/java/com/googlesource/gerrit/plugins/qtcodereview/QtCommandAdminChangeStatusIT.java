// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.reviewdb.client.Change;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCommandAdminChangeStatusIT extends QtCodeReviewIT {

  @Test
  public void New_Staged_Integrating_Merged_Abandoned_Deferred_New() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    String changeId = Integer.toString(c.getChange().getId().get());
    qtAdminChangeStatus(c, changeId, "new", "staged", Change.Status.STAGED);
    qtAdminChangeStatus(c, changeId, "staged", "integrating", Change.Status.INTEGRATING);
    qtAdminChangeStatus(c, changeId, "integrating", "merged", Change.Status.MERGED);
    qtAdminChangeStatus(c, changeId, "merged", "abandoned", Change.Status.ABANDONED);
    qtAdminChangeStatus(c, changeId, "abandoned", "deferred", Change.Status.DEFERRED);
    qtAdminChangeStatus(c, changeId, "deferred", "new", Change.Status.NEW);
  }

  @Test
  public void error_ChangeNotFound() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    String result;
    result = qtAdminChangeStatusExpectFail(c, "9999", "new", "staged", Change.Status.NEW);
    assertThat(result).contains("change not found");
  }

  @Test
  public void error_InvalidChangeId() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    String result;
    result = qtAdminChangeStatusExpectFail(c, "invalid", "new", "staged", Change.Status.NEW);
    assertThat(result).contains("change-id not numeric");
  }

  @Test
  public void error_Invalid_From_Status() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    String result;
    String changeId = Integer.toString(c.getChange().getId().get());
    result = qtAdminChangeStatusExpectFail(c, changeId, "invalid", "staged", Change.Status.NEW);
    assertThat(result).contains("invalid from status");
  }

  @Test
  public void error_Invalid_To_Status() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    String result;
    String changeId = Integer.toString(c.getChange().getId().get());
    result = qtAdminChangeStatusExpectFail(c, changeId, "new", "staaaaged", Change.Status.NEW);
    assertThat(result).contains("invalid to status");
  }

  @Test
  public void error_Wrong_From_Status() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    String result;
    String changeId = Integer.toString(c.getChange().getId().get());
    result = qtAdminChangeStatusExpectFail(c, changeId, "merged", "staged", Change.Status.NEW);
    assertThat(result).contains("change status was not");
  }

  @Test
  public void error_NoPermission() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    String changeId = Integer.toString(c.getChange().getId().get());

    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow change-status";
    commandStr += " --project " + project.get();
    commandStr += " --change-id " + changeId;
    commandStr += " --from new";
    commandStr += " --to merged";
    String resultStr = userSshSession.exec(commandStr);
    assertThat(userSshSession.getError()).contains("not permitted");

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.NEW);
  }

  private void qtAdminChangeStatus(
      PushOneCommit.Result c, String changeId, String from, String to, Change.Status expectedStatus)
      throws Exception {
    String result = qtAdminChangeStatusCommon(c, changeId, from, to, expectedStatus, false);
    assertThat(result).isNull();
  }

  private String qtAdminChangeStatusExpectFail(
      PushOneCommit.Result c, String changeId, String from, String to, Change.Status expectedStatus)
      throws Exception {
    return qtAdminChangeStatusCommon(c, changeId, from, to, expectedStatus, true);
  }

  private String qtAdminChangeStatusCommon(
      PushOneCommit.Result c,
      String changeId,
      String from,
      String to,
      Change.Status expectedStatus,
      Boolean expectFail)
      throws Exception {
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow change-status";
    commandStr += " --project " + project.get();
    commandStr += " --change-id " + changeId;
    commandStr += " --from " + from;
    commandStr += " --to " + to;
    String resultStr = adminSshSession.exec(commandStr);
    if (expectFail) assertThat(adminSshSession.getError()).isNotNull();
    else assertThat(adminSshSession.getError()).isNull();

    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(expectedStatus);
    return adminSshSession.getError();
  }
}
