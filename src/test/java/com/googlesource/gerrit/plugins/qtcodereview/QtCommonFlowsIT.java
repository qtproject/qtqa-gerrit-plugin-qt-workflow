// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.entities.BranchNameKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCommonFlowsIT extends QtCodeReviewIT {

  @Before
  public void SetDefaultPermissions() throws Exception {
    createBranch(BranchNameKey.create(project, "5.12"));

    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.QT_STAGE).ref("refs/heads/master").group(REGISTERED_USERS)).update();
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.QT_STAGE).ref("refs/heads/5.12").group(REGISTERED_USERS)).update();
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.PUSH).ref("refs/staging/*").group(adminGroupUuid())).update();
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.CREATE).ref("refs/builds/*").group(adminGroupUuid())).update();
  }

  @Test
  public void pingSSHTest() throws Exception {
    assertThat(adminSshSession.exec("gerrit-plugin-qt-workflow ping")).contains("Pong");
    assertThat(adminSshSession.getError()).isNull();
  }

  @Test
  public void emptyChange_Stage_Integrating_Merged() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "1st commit", "afile", "");
    approve(c.getChangeId());
    QtStage(c);

    // no changes in this commit
    c = pushCommit("master", "no content", "afile", "");
    approve(c.getChangeId());
    QtStage(c);
    assertStatusStaged(c.getChange().change());
    RevCommit stagingHead = getRemoteHead(project, "refs/staging/master");

    QtNewBuild("master", "master-build-000");
    assertStatusIntegrating(c.getChange().change());

    QtApproveBuild("master", "master-build-000");
    assertStatusMerged(c.getChange().change());

    String gitLog = getRemoteLog("refs/staging/master").toString();
    assertThat(gitLog).contains(stagingHead.getId().name());
  }

  @Test
  public void mergeCommit_Stage_Integrating_Merged() throws Exception {
    RevCommit initialHead = getRemoteHead();

    // make changes on feature branch
    PushOneCommit.Result f1 = pushCommit("5.12", "commitmsg1", "file1", "content1");
    approve(f1.getChangeId());
    gApi.changes().id(f1.getChangeId()).current().submit();
    PushOneCommit.Result f2 = pushCommit("5.12", "commitmsg2", "file2", "content2");
    approve(f2.getChangeId());
    gApi.changes().id(f2.getChangeId()).current().submit();

    // make a change on master branch
    testRepo.reset(initialHead);
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg3", "file3", "content3");
    approve(c1.getChangeId());
    gApi.changes().id(c1.getChangeId()).current().submit();

    // merge feature branch into master
    PushOneCommit mm = pushFactory.create(admin.newIdent(), testRepo);
    mm.setParents(ImmutableList.of(c1.getCommit(), f2.getCommit()));
    PushOneCommit.Result m = mm.to("refs/for/master");
    m.assertOkStatus();
    approve(m.getChangeId());
    QtStage(m);
    assertStatusStaged(m.getChange().change());

    QtNewBuild("master", "master-build-001");
    assertStatusIntegrating(m.getChange().change());

    QtApproveBuild("master", "master-build-001");
    assertStatusMerged(m.getChange().change());

    // check that all commits are in staging ref
    String gitLog = getRemoteLog("refs/heads/master").toString();
    assertThat(gitLog).contains(initialHead.getId().name());
    assertThat(gitLog).contains(c1.getCommit().getId().name());
    assertThat(gitLog).contains(f1.getCommit().getId().name());
    assertThat(gitLog).contains(f2.getCommit().getId().name());
    assertThat(gitLog).contains(m.getCommit().getId().name());
  }

  @Test
  public void multiBranch_New_Staged_Integrating_Merged() throws Exception {
    // Push 3 independent commits
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("5.12", "commitmsg2", "file2", "content2");
    testRepo.reset(initialHead);
    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");
    approve(c1.getChangeId());
    approve(c2.getChangeId());
    approve(c3.getChangeId());

    QtStage(c1);
    QtStage(c2);
    QtStage(c3);

    QtNewBuild("master", "master-build-100");
    assertStatusIntegrating(c1.getChange().change());
    assertStatusStaged(c2.getChange().change());

    QtNewBuild("5.12", "5.12-build-100");
    QtApproveBuild("5.12", "5.12-build-100");
    assertStatusIntegrating(c1.getChange().change());
    assertStatusIntegrating(c3.getChange().change());

    QtApproveBuild("master", "master-build-100");
    RevCommit branchHeadMaster = getRemoteHead(project, R_HEADS + "master");
    RevCommit branchHead_5_12 = getRemoteHead(project, R_HEADS + "5.12");
    assertThat(branchHeadMaster.getId()).isNotEqualTo(branchHead_5_12.getId());
  }

  @Test
  public void multiBranch_New_Staged_Integrating_Failed() throws Exception {
    // Push 3 independent commits
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("5.12", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("5.12", "commitmsg2", "file2", "content2");
    testRepo.reset(initialHead);
    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");
    approve(c1.getChangeId());
    approve(c2.getChangeId());
    approve(c3.getChangeId());

    QtStage(c1);
    QtStage(c2);
    QtStage(c3);

    QtNewBuild("5.12", "5.12-build-101");
    assertStatusIntegrating(c1.getChange().change());
    assertStatusStaged(c3.getChange().change());

    QtNewBuild("master", "master-build-101");
    QtFailBuild("master", "master-build-101");
    assertStatusIntegrating(c1.getChange().change());
    assertStatusIntegrating(c2.getChange().change());

    QtFailBuild("5.12", "5.12-build-101");
    RevCommit branchHeadMaster = getRemoteHead(project, R_HEADS + "master");
    RevCommit branchHead_5_12 = getRemoteHead(project, R_HEADS + "5.12");
    assertThat(branchHeadMaster.getId()).isEqualTo(branchHead_5_12.getId());
  }

  @Test
  public void multiBranch_UnStage() throws Exception {
    // Push 2 independent commits
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("5.12", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    QtStage(c1);
    QtStage(c2);

    QtUnStage(c1);
    assertStatusStaged(c2.getChange().change());

    QtUnStage(c2);
    RevCommit stagingHeadMaster = getRemoteHead(project, R_STAGING + "master");
    RevCommit stagingHead_5_12 = getRemoteHead(project, R_STAGING + "5.12");
    assertThat(stagingHeadMaster.getId()).isEqualTo(stagingHead_5_12.getId());
  }

  @Test
  public void newStageWhileBuilding_Pass_Pass() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    QtStage(c1);
    assertStatusNew(c2.getChange().change());

    QtNewBuild("master", "master-build-401");
    RevCommit build1Head = getRemoteHead(project, R_BUILDS + "master-build-401");
    assertStatusNew(c2.getChange().change());

    QtStage(c2);
    QtApproveBuild("master", "master-build-401");
    assertStatusStaged(c2.getChange().change());

    QtNewBuild("master", "master-build-402");
    RevCommit build2Head = getRemoteHead(project, R_BUILDS + "master-build-402");
    QtApproveBuild("master", "master-build-402");

    String gitLog = getRemoteLog("refs/heads/master").toString();
    assertThat(gitLog).contains(build1Head.getId().name());
    assertThat(gitLog).contains(build2Head.getId().name());

    RevCommit updatedHead = getRemoteHead();
    assertThat(updatedHead.getShortMessage()).isEqualTo(c2.getCommit().getShortMessage());
  }

  @Test
  public void newStageWhileBuilding_Pass_Fail() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    QtStage(c1);
    assertStatusNew(c2.getChange().change());

    QtNewBuild("master", "master-build-401");
    RevCommit build1Head = getRemoteHead(project, R_BUILDS + "master-build-401");
    assertStatusNew(c2.getChange().change());

    QtStage(c2);
    QtApproveBuild("master", "master-build-401");
    assertStatusStaged(c2.getChange().change());

    QtNewBuild("master", "master-build-402");
    RevCommit build2Head = getRemoteHead(project, R_BUILDS + "master-build-402");
    QtFailBuild("master", "master-build-402");

    String gitLog = getRemoteLog("refs/heads/master").toString();
    assertThat(gitLog).contains(build1Head.getId().name());
    assertThat(gitLog).doesNotContain(build2Head.getId().name());

    RevCommit updatedHead = getRemoteHead();
    assertThat(updatedHead.getShortMessage()).isEqualTo(c1.getCommit().getShortMessage());
  }

  @Test
  public void newStageWhileBuilding_Fail_Pass() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    QtStage(c1);
    assertStatusNew(c2.getChange().change());

    QtNewBuild("master", "master-build-401");
    RevCommit build1Head = getRemoteHead(project, R_BUILDS + "master-build-401");
    assertStatusNew(c2.getChange().change());

    QtStage(c2);
    QtFailBuild("master", "master-build-401");
    assertStatusStaged(c2.getChange().change());

    QtNewBuild("master", "master-build-402");
    RevCommit build2Head = getRemoteHead(project, R_BUILDS + "master-build-402");
    QtApproveBuild("master", "master-build-402");
    assertStatusNew(c1.getChange().change());
    ;

    String gitLog = getRemoteLog("refs/heads/master").toString();
    assertThat(gitLog).doesNotContain(build1Head.getId().name());
    assertThat(gitLog).contains(build2Head.getId().name());

    RevCommit updatedHead = getRemoteHead();
    assertThat(updatedHead.getShortMessage()).isEqualTo(c2.getCommit().getShortMessage());
  }

  @Test
  public void newStageWhileBuilding_Fail_Fail() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    QtStage(c1);
    assertStatusNew(c2.getChange().change());

    QtNewBuild("master", "master-build-401");
    assertStatusNew(c2.getChange().change());

    QtStage(c2);
    QtFailBuild("master", "master-build-401");
    assertStatusStaged(c2.getChange().change());

    QtNewBuild("master", "master-build-402");
    QtFailBuild("master", "master-build-402");

    RevCommit updatedHead = getRemoteHead();
    assertThat(updatedHead).isEqualTo(initialHead);
  }

  @Test
  public void unStageWhileBuilding() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c1.getChangeId());
    QtStage(c1);
    QtNewBuild("master", "master-build-402");

    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c2.getChangeId());
    QtStage(c2);
    RevCommit stagingExpected = getRemoteHead(project, R_STAGING + "master");

    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");
    approve(c3.getChangeId());
    QtStage(c3);

    QtUnStage(c3);
    RevCommit stagingHead = getRemoteHead(project, R_STAGING + "master");
    assertThat(stagingHead).isEqualTo(stagingExpected);

    assertStatusIntegrating(c1.getChange().change());
    assertStatusStaged(c2.getChange().change());
    assertStatusNew(c3.getChange().change());
  }

  @Test
  public void unStage_MergeConflict_While_Building() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    testRepo.reset(initialHead);
    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "thesamefile", "content3");
    // c4 depends on c3
    PushOneCommit.Result c4 =
        pushCommit("master", "commitmsg4", "thesamefile", "conflict content3");
    approve(c1.getChangeId());
    approve(c2.getChangeId());
    approve(c3.getChangeId());
    approve(c4.getChangeId());

    QtStage(c1);
    QtNewBuild("master", "master-build-500");
    RevCommit stagingExpected = getRemoteHead(project, R_STAGING + "master");

    QtStage(c2);
    QtStage(c3);
    QtStage(c4);

    QtUnStage(c3);
    assertStatusIntegrating(c1.getChange().change());
    assertStatusNew(c2.getChange().change());
    assertStatusNew(c3.getChange().change());
    assertStatusNew(c4.getChange().change());

    RevCommit stagingHeadMaster = getRemoteHead(project, R_STAGING + "master");
    assertThat(stagingHeadMaster.getId()).isEqualTo(stagingExpected.getId());
  }
}
