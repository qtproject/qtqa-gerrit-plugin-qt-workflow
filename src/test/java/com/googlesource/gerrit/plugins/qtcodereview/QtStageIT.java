// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import java.util.ArrayList;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtStageIT extends QtCodeReviewIT {

  private final String STAGED_MSG = "Staged for CI";

  @Before
  public void SetDefaultPermissions() throws Exception {
    createBranch(new Branch.NameKey(project, "feature"));

    grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
    grant(project, "refs/heads/feature", Permission.QT_STAGE, false, REGISTERED_USERS);
  }

  @Test
  public void singleChange_Stage() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    RevCommit stagingHead = qtStage(c);
    assertApproval(c.getChangeId(), admin);
  }

  @Test
  public void multiChange_Stage() throws Exception {
    RevCommit initialHead;
    RevCommit stagingHead;

    // Push 3 independent commits
    initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    testRepo.reset(initialHead);
    PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

    approve(c1.getChangeId());
    approve(c2.getChangeId());
    approve(c3.getChangeId());

    stagingHead = qtStage(c1);
    stagingHead = qtStage(c2, stagingHead);
    stagingHead = qtStage(c3, stagingHead);
  }

  @Test
  public void mergeCommit_Stage() throws Exception {
    RevCommit initialHead = getRemoteHead();

    // make changes on feature branch
    PushOneCommit.Result f1 = pushCommit("feature", "commitmsg1", "file1", "content1");
    PushOneCommit.Result f2 = pushCommit("feature", "commitmsg2", "file2", "content2");
    approve(f1.getChangeId());
    gApi.changes().id(f1.getChangeId()).current().submit();
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
    RevCommit stagingHead = qtStageExpectMergeFastForward(m);

    // check that all commits are in staging ref
    String gitLog = getRemoteLog("refs/staging/master").toString();
    assertThat(gitLog).contains(initialHead.getId().name());
    assertThat(gitLog).contains(c1.getCommit().getId().name());
    assertThat(gitLog).contains(f1.getCommit().getId().name());
    assertThat(gitLog).contains(f2.getCommit().getId().name());
    assertThat(gitLog).contains(m.getCommit().getId().name());
  }

  @Test
  public void mergeCommit_Stage_ExpectMergeOfMerge() throws Exception {
    RevCommit initialHead = getRemoteHead();

    // make changes on feature branch
    PushOneCommit.Result f1 = pushCommit("feature", "commitmsg1", "file1", "content1");
    PushOneCommit.Result f2 = pushCommit("feature", "commitmsg2", "file2", "content2");
    approve(f1.getChangeId());
    gApi.changes().id(f1.getChangeId()).current().submit();
    approve(f2.getChangeId());
    gApi.changes().id(f2.getChangeId()).current().submit();

    // make a change on master branch
    testRepo.reset(initialHead);
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg3", "file3", "content3");
    approve(c1.getChangeId());
    gApi.changes().id(c1.getChangeId()).current().submit();

    // merge feature branch into master
    PushOneCommit mm = pushFactory.create(admin.newIdent(), testRepo);
    mm.setParents(ImmutableList.of(f2.getCommit(), c1.getCommit()));
    PushOneCommit.Result m = mm.to("refs/for/master");
    m.assertOkStatus();
    approve(m.getChangeId());
    RevCommit stagingHead = qtStageExpectMergeOfMerge(m);

    // check that all commits are in staging ref
    String gitLog = getRemoteLog("refs/staging/master").toString();
    assertThat(gitLog).contains(initialHead.getId().name());
    assertThat(gitLog).contains(c1.getCommit().getId().name());
    assertThat(gitLog).contains(f1.getCommit().getId().name());
    assertThat(gitLog).contains(f2.getCommit().getId().name());
    assertThat(gitLog).contains(m.getCommit().getId().name());
  }

  @Test
  public void emptyChange_Stage() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "1st commit", "afile", "");
    approve(c.getChangeId());
    RevCommit stagingHead = qtStage(c);
    assertApproval(c.getChangeId(), admin);

    // no changes in this commit
    c = pushCommit("master", "no content", "afile", "");
    approve(c.getChangeId());
    stagingHead = qtStage(c, stagingHead);
    assertApproval(c.getChangeId(), admin);
  }

  @Test
  public void errorStage_No_Permission() throws Exception {
    deny(project, "refs/heads/master", Permission.QT_STAGE, REGISTERED_USERS);

    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    RestResponse response = qtStageExpectFail(c, initialHead, initialHead, HttpStatus.SC_FORBIDDEN);
    assertThat(response.getEntityContent()).contains("not permitted");

    grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
  }

  @Test
  public void errorStage_Wrong_Status() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    grant(project, "refs/heads/master", Permission.ABANDON, false, REGISTERED_USERS);
    QtDefer(c);
    deny(project, "refs/heads/master", Permission.ABANDON, REGISTERED_USERS);

    RestResponse response = qtStageExpectFail(c, initialHead, initialHead, HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).contains("Change is DEFERRED");
  }

  @Test
  public void errorStage_Invalid_ChangeId() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    RestResponse response = call_REST_API_Stage("thischangeidnotfound", c.getCommit().getName());
    response.assertStatus(HttpStatus.SC_NOT_FOUND);
    assertThat(response.getEntityContent()).contains("Not found: thischangeidnotfound");
  }

  @Test
  public void errorStage_Invalid_RevisionId() throws Exception {
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    RestResponse response = call_REST_API_Stage(c.getChangeId(), "thisrevisionidnotfound");
    response.assertStatus(HttpStatus.SC_NOT_FOUND);
    assertThat(response.getEntityContent()).contains("Not found: thisrevisionidnotfound");
  }

  @Test
  public void errorStage_Revision_Not_Current() throws Exception {
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    PushOneCommit.Result c2 = amendCommit(c1.getChangeId());

    RestResponse response = call_REST_API_Stage(c1.getChangeId(), c1.getCommit().getName());
    response.assertStatus(HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).contains("is not current");
  }

  @Test
  public void errorStage_Not_Reviewed() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

    RestResponse response = qtStageExpectFail(c, initialHead, initialHead, HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).contains("needs Code-Review");
  }

  @Test
  public void errorStage_Parent_Not_Merged() throws Exception {
    RevCommit initialHead = getRemoteHead();

    // make a change on feature branch without submit
    final PushOneCommit.Result f1 = pushCommit("feature", "f1-commitmsg", "f1-file", "f1-content");

    // merge feature branch into master
    final PushOneCommit mm = pushFactory.create(admin.newIdent(), testRepo);
    mm.setParents(ImmutableList.of(f1.getCommit(), initialHead));
    final PushOneCommit.Result m = mm.to("refs/for/master");
    approve(m.getChangeId());

    qtStageExpectFail(m, initialHead, initialHead, HttpStatus.SC_CONFLICT);
  }

  @Test
  public void errorAmend_Status_Staged() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c1.getChangeId());
    RevCommit stagingHead = qtStage(c1);

    PushOneCommit.Result c2 = amendCommit(c1.getChangeId());
    c2.assertErrorStatus(" closed");

    RevCommit updatedHead = getRemoteHead(project, "refs/staging/master");
    assertThat(updatedHead.getId()).isEqualTo(stagingHead.getId()); // not updated
  }

  @Test
  public void errorStage_Merge_Conlict() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "thesamefile", "content");
    approve(c1.getChangeId());
    RevCommit stagingHead1 = qtStage(c1);

    testRepo.reset(initialHead);
    PushOneCommit.Result c2 =
        pushCommit("master", "commitmsg2", "thesamefile", "conficting content");
    approve(c2.getChangeId());
    RestResponse response =
        qtStageExpectFail(c2, initialHead, stagingHead1, HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).contains("merge conflict");

    assertStatusNew(c2.getChange().change());
  }

  private RevCommit qtStage(PushOneCommit.Result c) throws Exception {
    return qtStage(c, false, false, null);
  }

  private RevCommit qtStage(PushOneCommit.Result c, RevCommit base) throws Exception {
    return qtStage(c, false, false, base);
  }

  private RevCommit qtStageExpectMergeFastForward(PushOneCommit.Result c) throws Exception {
    return qtStage(c, true, true, null);
  }

  private RevCommit qtStageExpectMergeOfMerge(PushOneCommit.Result c) throws Exception {
    return qtStage(c, true, false, null);
  }

  private RevCommit qtStage(
      PushOneCommit.Result c, boolean merge, boolean fastForward, RevCommit base) throws Exception {
    String branch = getBranchNameFromRef(c.getChange().change().getDest().get());
    String stagingRef = R_STAGING + branch;
    String branchRef = R_HEADS + branch;
    RevCommit originalCommit = c.getCommit();
    RevCommit initialHead = getRemoteHead(project, branchRef);
    RevCommit oldStagingHead = getRemoteRefHead(project, stagingRef);
    if (oldStagingHead == null) oldStagingHead = initialHead;

    RestResponse response = call_REST_API_Stage(c.getChangeId(), originalCommit.getName());
    response.assertOK();

    RevCommit branchHead = getRemoteHead(project, branchRef);
    assertThat(branchHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);
    assertReviewedByFooter(stagingHead, true);

    if (fastForward) {
      assertThat(stagingHead).isEqualTo(originalCommit);
    } else if (merge) {
      assertThat(stagingHead.getParentCount()).isEqualTo(2);
      assertThat(stagingHead.getParent(1)).isEqualTo(originalCommit);
    } else {
      assertCherryPick(stagingHead, originalCommit, base);
    }
    assertThat(stagingHead.getParent(0)).isEqualTo(oldStagingHead);
    assertRefUpdatedEvents(stagingRef, oldStagingHead, stagingHead);
    resetEvents();

    assertStatusStaged(c.getChange().change());

    ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage())
        .isEqualTo(STAGED_MSG); // check last message

    return stagingHead;
  }

  private RestResponse qtStageExpectFail(
      PushOneCommit.Result c, RevCommit initialHead, RevCommit oldStagingHead, int expectedStatus)
      throws Exception {
    String branch = getBranchNameFromRef(c.getChange().change().getDest().get());
    String stagingRef = R_STAGING + branch;
    String branchRef = R_HEADS + branch;

    RestResponse response = call_REST_API_Stage(c.getChangeId(), c.getCommit().getName());
    response.assertStatus(expectedStatus);

    RevCommit branchHead = getRemoteHead(project, branchRef);
    assertThat(branchHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

    RevCommit stagingHead = getRemoteRefHead(project, stagingRef);
    if (stagingHead != null)
      assertThat(stagingHead.getId()).isEqualTo(oldStagingHead.getId()); // staging is not updated

    assertRefUpdatedEvents(branchRef); // no events
    assertRefUpdatedEvents(stagingRef); // no events

    return response;
  }
}
