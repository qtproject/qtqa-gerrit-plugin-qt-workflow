// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import java.io.StringBufferInputStream;
import java.util.ArrayList;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCommandBuildApproveIT extends QtCodeReviewIT {

  private final String MERGED_MSG = "thebuildwasapproved";
  private final String FAILED_MSG = "thebuildfailed";

  @Before
  public void SetDefaultPermissions() throws Exception {
    createBranch(new Branch.NameKey(project, "feature"));

    grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
    grant(project, "refs/heads/feature", Permission.QT_STAGE, false, REGISTERED_USERS);
    grant(project, "refs/staging/*", Permission.PUSH, false, adminGroupUuid());
    grant(project, "refs/builds/*", Permission.CREATE, false, adminGroupUuid());
  }

  @Test
  public void singleChange_New_Staged_Integrating_Merged() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-100");

    RevCommit updatedHead = qtApproveBuild("master", "test-build-100", c, null);
  }

  @Test
  public void multiChange_New_Staged_Integrating_Merged() throws Exception {
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
    QtStage(c1);
    QtStage(c2);
    QtStage(c3);

    QtNewBuild("master", "test-build-101");

    RevCommit updatedHead = qtApproveBuild("master", "test-build-101", c3, null);
    assertStatusMerged(c1.getChange().change());
    assertStatusMerged(c2.getChange().change());
  }

  @Test
  public void singleChange_New_Staged_Integrating_Fail() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-200");

    RevCommit updatedHead = qtFailBuild("master", "test-build-200", c, initialHead);
  }

  @Test
  public void cherryPicked_Stays_Intact_After_Merge_And_Build() throws Exception {
    // make a change on feature branch
    final PushOneCommit.Result f1 = pushCommit("feature", "f1-commitmsg", "f1-file", "f1-content");
    approve(f1.getChangeId());
    gApi.changes().id(f1.getCommit().getName()).current().submit();

    // cherry pick it to the master branch (now there are two changes with same change-id)
    final ChangeInfo cp = cherryPick(f1, "master");

    // make another change on feature branch
    final PushOneCommit.Result f2 = pushCommit("feature", "f2-commitmsg", "f2-file", "f2-content");
    approve(f2.getChangeId());
    QtStage(f2);
    QtNewBuild("feature", "feature-build-000");
    QtApproveBuild("feature", "feature-build-000");

    // make a change on master branch
    final PushOneCommit.Result m1 = pushCommit("master", "m1-commitmsg", "m1-file", "m1-content");
    approve(m1.getChangeId());
    QtStage(m1);
    QtNewBuild("master", "master-build-000");
    QtApproveBuild("master", "master-build-000");

    // merge feature branch into master
    final PushOneCommit mm = pushFactory.create(admin.newIdent(), testRepo);
    mm.setParents(ImmutableList.of(f2.getCommit(), m1.getCommit()));
    final PushOneCommit.Result m = mm.to("refs/for/master");
    m.assertOkStatus();
    approve(m.getChangeId());
    QtStage(m);
    QtNewBuild("master", "merge-build-000");
    QtApproveBuild("master", "merge-build-000");

    final Changes changes = gApi.changes();
    assertThat(changes.id(project.get(), "feature", f1.getChangeId()).get(CURRENT_REVISION).status).isEqualTo(ChangeStatus.MERGED);
    assertThat(changes.id(project.get(), "feature", f2.getChangeId()).get(CURRENT_REVISION).status).isEqualTo(ChangeStatus.MERGED);
    assertThat(changes.id(project.get(), "master", m1.getChangeId()).get(CURRENT_REVISION).status).isEqualTo(ChangeStatus.MERGED);
    assertThat(changes.id(project.get(), "master", m.getChangeId()).get(CURRENT_REVISION).status).isEqualTo(ChangeStatus.MERGED);
    assertThat(changes.id(project.get(), "master", cp.changeId).get(CURRENT_REVISION).status).isEqualTo(ChangeStatus.NEW);
  }

  @Test
  public void multiChange_New_Staged_Integrating_Failed() throws Exception {
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
    QtStage(c1);
    QtStage(c2);
    QtStage(c3);

    QtNewBuild("master", "test-build-201");

    RevCommit updatedHead = qtFailBuild("master", "test-build-201", c3, initialHead);
    assertStatusNew(c1.getChange().change());
    assertStatusNew(c2.getChange().change());
  }

  @Test
  public void errorApproveBuild_NoPermission() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-600");

    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch master";
    commandStr += " --build-id test-build-600";
    commandStr += " --result pass";
    commandStr += " --message " + MERGED_MSG;
    String resultStr = userSshSession.exec(commandStr);
    assertThat(userSshSession.getError()).contains("not authorized");
  }

  @Test
  public void errorApproveBuild_RepoNotFound() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-600");

    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project notarepo";
    commandStr += " --branch master";
    commandStr += " --build-id test-build-600";
    commandStr += " --result pass";
    commandStr += " --message " + MERGED_MSG;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).contains("project not found");
  }

  @Test
  public void errorApproveBuild_WrongParameter() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-601");

    String resultStr = qtApproveBuildExpectFail("maybe", "master", "test-build-601");
    assertThat(resultStr).contains("result argument accepts only value pass or fail");
  }

  @Test
  public void errorApproveBuild_NoChanges() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-602");
    RevCommit updatedHead = qtApproveBuild("master", "test-build-602", c, null);

    String resultStr = qtApproveBuildExpectFail("pass", "master", "test-build-602");
    assertThat(resultStr).contains("No open changes in the build branch");
  }

  @Test
  public void errorApproveBuild_NonExistingBranch() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-603");

    String resultStr = qtApproveBuildExpectFail("pass", "invalidbranch", "test-build-603");
    assertThat(resultStr).contains("branch not found");
  }

  @Test
  public void errorApproveBuild_NonExistingBuild() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-604");

    String resultStr = qtApproveBuildExpectFail("pass", "master", "doesnotexist");
    assertThat(resultStr).contains("build not found");
  }

  @Test
  public void errorApproveBuild_FastForwardFail() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-605");

    // direct push that causes fast forward failure
    testRepo.reset(initialHead);
    PushOneCommit.Result d = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(d.getChangeId());
    gApi.changes().id(d.getChangeId()).current().submit();
    RevCommit branchHead = getRemoteHead();

    String stagingRef = R_STAGING + "master";
    String branchRef = R_HEADS + "master";
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch master";
    commandStr += " --build-id test-build-605";
    commandStr += " --result pass";
    commandStr += " --message " + MERGED_MSG;
    adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();

    RevCommit updatedHead = getRemoteHead(project, branchRef);
    assertThat(updatedHead).isEqualTo(branchHead); // master is not updated
    RevCommit stagingHead = getRemoteHead(project, stagingRef);
    assertThat(stagingHead).isEqualTo(branchHead); // staging is updated to branch head

    assertStatusNew(c.getChange().change());
    Change change = d.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.MERGED);
  }

  @Test
  public void approveBuild_MultiLineMessage() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c);
    QtNewBuild("master", "test-build-605");

    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch master";
    commandStr += " --build-id test-build-605";
    commandStr += " --result pass";
    commandStr += " --message -";
    String multiMessage = "the build\nwas\n\"approved\"\n";
    StringBufferInputStream input = new StringBufferInputStream(multiMessage);

    String resultStr = adminSshSession.exec(commandStr, input);
    assertThat(resultStr).isEqualTo("");
    assertThat(adminSshSession.getError()).isNull();

    ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage())
        .isEqualTo(multiMessage); // check last message
  }

  private RevCommit qtApproveBuild(
      String branch, String buildId, PushOneCommit.Result expectedContent, RevCommit expectedHead)
      throws Exception {
    String stagingRef = R_STAGING + branch;
    String branchRef = R_HEADS + branch;
    String buildRef = R_BUILDS + buildId;

    RevCommit stagingHeadOld = getRemoteRefHead(project, stagingRef);
    RevCommit initialHead = getRemoteHead(project, branchRef);
    String commandStr;

    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch " + branch;
    commandStr += " --build-id " + buildId;
    commandStr += " --result pass";
    commandStr += " --message " + MERGED_MSG;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();

    RevCommit buildHead = getRemoteHead(project, buildRef);
    assertThat(buildHead.getId()).isNotNull(); // build ref is still there
    assertReviewedByFooter(buildHead, true);

    RevCommit updatedHead = getRemoteHead(project, branchRef);
    if (expectedContent != null && expectedHead == null) {
      RevCommit commit = expectedContent.getCommit();
      assertCherryPick(updatedHead, commit, null);
      expectedHead = updatedHead;
    } else {
      assertThat(updatedHead).isEqualTo(expectedHead); // master is updated
    }

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);
    assertThat(stagingHead).isEqualTo(stagingHeadOld); // staging remains the same

    assertRefUpdatedEvents(branchRef, initialHead, expectedHead);
    resetEvents();

    assertStatusMerged(expectedContent.getChange().change());

    ArrayList<ChangeMessage> messages = new ArrayList(expectedContent.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage())
        .isEqualTo(MERGED_MSG); // check last message

    return updatedHead;
  }

  private RevCommit qtFailBuild(
      String branch, String buildId, PushOneCommit.Result c, RevCommit expectedStagingHead)
      throws Exception {
    String stagingRef = R_STAGING + branch;
    String branchRef = R_HEADS + branch;
    String buildRef = R_BUILDS + buildId;
    RevCommit stagingHeadOld = getRemoteRefHead(project, stagingRef);
    RevCommit initialHead = getRemoteHead(project, branchRef);
    String commandStr;

    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch " + branch;
    commandStr += " --build-id " + buildId;
    commandStr += " --result fail";
    commandStr += " --message " + FAILED_MSG;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();

    RevCommit updatedHead = getRemoteHead(project, branchRef);
    assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);

    if (c != null && expectedStagingHead == null) {
      assertCherryPick(stagingHead, c.getCommit(), null);
      expectedStagingHead = stagingHead;
    }
    assertThat(stagingHead).isEqualTo(expectedStagingHead); // staging is rebuild

    RevCommit buildHead = getRemoteHead(project, buildRef);
    assertThat(buildHead.getId()).isNotNull(); // build ref is still there

    assertRefUpdatedEvents(stagingRef, stagingHeadOld, stagingHead); // staging is rebuild

    assertStatusNew(c.getChange().change());

    ArrayList<ChangeMessage> messages = new ArrayList<ChangeMessage>(c.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage())
        .isEqualTo(FAILED_MSG); // check last message

    return updatedHead;
  }

  private String qtApproveBuildExpectFail(String cmd, String branch, String buildId)
      throws Exception {
    String stagingRef = R_STAGING + branch;
    String branchRef = R_HEADS + branch;
    RevCommit initialHead = getRemoteRefHead(project, branchRef);
    RevCommit stagingHeadOld = getRemoteRefHead(project, stagingRef);
    String commandStr;

    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch " + branch;
    commandStr += " --build-id " + buildId;
    commandStr += " --result " + cmd;
    commandStr += " --message " + MERGED_MSG;
    String resultStr = adminSshSession.exec(commandStr);

    RevCommit updatedHead = getRemoteRefHead(project, branchRef);
    if (updatedHead != null)
      assertThat(updatedHead).isEqualTo(initialHead); // master is not updated

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);
    if (stagingHead != null)
      assertThat(stagingHead).isEqualTo(stagingHeadOld); // staging is not updated

    return adminSshSession.getError();
  }
}
