// Copyright (C) 2019-25 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.ChangeMessage;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.client.ChangeStatus;
import java.util.ArrayList;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
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
    createBranch(BranchNameKey.create(project, "feature"));

    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.QT_STAGE).ref("refs/heads/master").group(REGISTERED_USERS)).update();
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.QT_STAGE).ref("refs/heads/feature").group(REGISTERED_USERS)).update();
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

  private void createAndStageCommit(String message, Integer index, Boolean expectPass)
      throws Exception  {
    Integer responseStatus = expectPass ? HttpStatus.SC_OK : HttpStatus.SC_CONFLICT;
    ChangeStatus changeStatus = expectPass ? ChangeStatus.STAGED : ChangeStatus.NEW;
    String expectedChangeId = expectPass ?
        "I000000000000000000000000000000000000100" + String.valueOf(index):
        "I000000000000000000000000000000000000200" + String.valueOf(index);

    RevCommit rc = commitBuilder().add("a.txt", "1").message(message).create();
    PushResult r = pushHead(testRepo, "refs/for/master");
    RemoteRefUpdate refUpdate = r.getRemoteUpdate("refs/for/master");
    assertThat(refUpdate.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

    String changeId = GitUtil.getChangeId(testRepo, refUpdate.getNewObjectId()).get().trim();
    assertThat(changeId).isEqualTo(expectedChangeId);
    approve(changeId);
    ChangeInfo c = gApi.changes().id(changeId).get(CURRENT_REVISION, CURRENT_COMMIT);
    assertThat(c.status).isEqualTo(ChangeStatus.NEW);

    RestResponse response = call_REST_API_Stage(c.id, c.currentRevision);
    response.assertStatus(responseStatus);
    if (!expectPass)
      assertThat(response.getEntityContent()).contains("Extra ");

    c = gApi.changes().id(changeId).get(CURRENT_REVISION, CURRENT_COMMIT);
    assertThat(c.status).isEqualTo(changeStatus);
  }

@Test
public void errorStage_Validate_Commit_Message() throws Exception {

    String[] validCommitMessages = {
      "Summary\n\nDetails\nChange-Id: I0000000000000000000000000000000000001000\n",
      "Summary\n\n\nChange-Id: I0000000000000000000000000000000000001001\n",
      "Summary\n \n  \nChange-Id: I0000000000000000000000000000000000001002\n",
      "Summary\n\nChange-Id: I0000000000000000000000000000000000001003\n"
    };

    String[] inValidCommitMessages = {
      "Summary\nDetails\nChange-Id: I0000000000000000000000000000000000002000\n\n",
      "Summary\n\nChange-Id: I0000000000000000000000000000000000002001\n \n",
      "Summary\n\n\nChange-Id: I0000000000000000000000000000000000002002\n ",
      "Summary\n \nChange-Id: I0000000000000000000000000000000000002003 \n"
    };

    for (int i = 0; i < validCommitMessages.length; i++) {
      createAndStageCommit(validCommitMessages[i], i, true);
    }

    for (int i = 0; i < inValidCommitMessages.length; i++) {
      createAndStageCommit(inValidCommitMessages[i], i,  false);
    }
  }

  @Test
  public void errorStage_No_Permission() throws Exception {
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.deny(Permission.QT_STAGE).ref("refs/heads/master").group(REGISTERED_USERS)).update();

    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    RestResponse response = qtStageExpectFail(c, initialHead, initialHead, HttpStatus.SC_FORBIDDEN);
    assertThat(response.getEntityContent()).contains("not permitted");

    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.QT_STAGE).ref("refs/heads/master").group(REGISTERED_USERS)).update();
  }

  @Test
  public void errorStage_Wrong_Status() throws Exception {
    RevCommit initialHead = getRemoteHead();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    projectOperations.project(project).forUpdate().add(TestProjectUpdate.allow(Permission.ABANDON).ref("refs/heads/master").group(REGISTERED_USERS)).update();
    QtDefer(c);
    projectOperations.project(project).forUpdate().add(TestProjectUpdate.deny(Permission.ABANDON).ref("refs/heads/master").group(REGISTERED_USERS)).update();

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
    assertThat(response.getEntityContent()).contains("submit requirement 'Code-Review' is unsatisfied");
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
    String branch = getBranchNameFromRef(c.getChange().change().getDest().branch());
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
    String branch = getBranchNameFromRef(c.getChange().change().getDest().branch());
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
