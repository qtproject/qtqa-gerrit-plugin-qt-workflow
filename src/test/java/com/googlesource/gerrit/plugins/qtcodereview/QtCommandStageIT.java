// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.client.Change;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCommandStageIT extends QtCodeReviewIT {

  @Before
  public void SetDefaultPermissions() throws Exception {
    grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
  }

  @Test
  public void single_Change_Stage() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    qtSshStage(c);
  }

  @Test
  public void multi_Change_Stage() throws Exception {
    // Push 3 independent commits
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    testRepo.reset(initialHead);
    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

    approve(c1.getChangeId());
    approve(c2.getChangeId());
    approve(c3.getChangeId());

    String changes;
    changes =
        String.valueOf(c1.getPatchSetId().getParentKey().get()) + "," + c1.getPatchSetId().getId();
    changes +=
        " "
            + String.valueOf(c2.getPatchSetId().getParentKey().get())
            + ","
            + c2.getPatchSetId().getId();
    changes +=
        " "
            + String.valueOf(c3.getPatchSetId().getParentKey().get())
            + ","
            + c3.getPatchSetId().getId();
    String result = qtSshStageCommon(c1, changes, false);
    assertThat(result).isNull();
  }

  @Test
  public void error_InvalidPatch() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    String changes = "invalid";
    String result = qtSshStageCommon(c, changes, true);
    assertThat(result).contains("is not a valid patch set");
  }

  @Test
  public void error_ChangeNotFound() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    String changes = "9999,1";
    String result = qtSshStageCommon(c, changes, true);
    assertThat(result).contains("no such change");
  }

  @Test
  public void error_PatchNotFound() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    String changes = String.valueOf(c.getPatchSetId().getParentKey().get()) + ",9999";
    String result = qtSshStageCommon(c, changes, true);
    assertThat(result).contains("no such patch set");
  }

  @Test
  public void error_PatchNotCurrent() throws Exception {
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    PushOneCommit.Result c2 = amendCommit(c1.getChangeId());
    approve(c2.getChangeId());

    String changes = String.valueOf(c2.getPatchSetId().getParentKey().get()) + ",1";
    String result = qtSshStageCommon(c2, changes, true);
    assertThat(result).contains("is not current");
  }

  @Test
  public void error_Wrong_Status() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
    QtDefer(c);
    deny(project, "refs/heads/master", Permission.ABANDON, REGISTERED_USERS);

    String result = qtSshStageExpectFail(c);
    assertThat(result).contains("Change is DEFERRED");
  }

  @Test
  public void error_NoPermission() throws Exception {
    deny(project, "refs/heads/master", Permission.QT_STAGE, REGISTERED_USERS);
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    String result = qtSshStageExpectFail(c);
    assertThat(result).contains("not permitted");
    grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
  }

  @Test
  public void error_notReviewed() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    String result = qtSshStageExpectFail(c);
    assertThat(result).contains("needs Code-Review");
  }

  private void qtSshStage(PushOneCommit.Result c) throws Exception {
    qtSshStageCommon(c, false);
  }

  private String qtSshStageExpectFail(PushOneCommit.Result c) throws Exception {
    return qtSshStageCommon(c, true);
  }

  private String qtSshStageCommon(PushOneCommit.Result c, Boolean expectFail) throws Exception {
    String changes;
    changes = String.valueOf(c.getPatchSetId().getParentKey().get()) + ",";
    changes += c.getPatchSetId().getId();

    return qtSshStageCommon(c, changes, expectFail);
  }

  private String qtSshStageCommon(PushOneCommit.Result c, String changes, Boolean expectFail)
      throws Exception {
    String commandStr = "gerrit-plugin-qt-workflow stage " + changes;

    String resultStr = adminSshSession.exec(commandStr);
    if (expectFail) assertThat(adminSshSession.getError()).isNotNull();
    else assertThat(adminSshSession.getError()).isNull();

    Change change = c.getChange().change();
    if (expectFail) assertThat(change.getStatus()).isNotEqualTo(Change.Status.STAGED);
    else assertStatusStaged(change);

    return adminSshSession.getError();
  }
}
