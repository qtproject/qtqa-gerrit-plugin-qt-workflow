// Copyright (C) 2021 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.GitUtil.assertPushOk;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;

import com.google.common.collect.Lists;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import java.util.List;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
public class QtCodeReviewIT extends LightweightPluginDaemonTest {
  @Inject public ProjectOperations projectOperations;

  protected static final String R_HEADS = "refs/heads/";
  protected static final String R_STAGING = "refs/staging/";
  protected static final String R_BUILDS = "refs/builds/";
  protected static final String R_PUSH = "refs/for/";

  protected static final String CONTENT_DATA = "hereisjustsomecontentforthecommits";
  protected static final String BUILD_PASS_MESSAGE = "thebuildpassed";
  protected static final String BUILD_FAIL_MESSAGE = "thebuildfailed";

  private RevCommit lastGeneratedCommit;

  @Before
  public void ReduceLogging() throws Exception {
    LogManager.resetConfiguration();

    final PatternLayout layout = new PatternLayout();
    layout.setConversionPattern("%-5p %c %x: %m%n");

    final ConsoleAppender dst = new ConsoleAppender();
    dst.setLayout(layout);
    dst.setTarget("System.err");
    dst.setThreshold(Level.INFO);
    dst.activateOptions();

    final Logger root = LogManager.getRootLogger();
    root.removeAllAppenders();
    root.addAppender(dst);
  }

  @Before
  public void GenerateCommits() throws Exception {
    // Generate 100+ commits to match more like production environment
    RevCommit parent = getRemoteHead();
    for (int i = 0; i <= 101; i++) {
      RevCommit c = testRepo.commit()
          .message("a commit " + String.valueOf(i))
          .add("afile" + String.valueOf(i), "somecontent")
          .parent(parent)
          .insertChangeId()
          .create();
      testRepo.reset(c);
      assertPushOk(pushHead(testRepo, "refs/heads/master", false), "refs/heads/master");
      parent = c;
    }
    lastGeneratedCommit = parent;
    resetEvents();
  }

  @Test
  public void dummyTest() throws Exception {}

  // Helper functions

  protected void QtDefer(PushOneCommit.Result c) throws Exception {
    RestResponse response = call_REST_API_Defer(c.getChangeId());
    response.assertOK();
    Change change = c.getChange().change();
    assertThat(change.getStatus()).isEqualTo(Change.Status.DEFERRED);
  }

  protected RestResponse call_REST_API_Defer(String changeId) throws Exception {
    String url = "/changes/" + changeId + "/gerrit-plugin-qt-workflow~defer";
    RestResponse response = userRestSession.post(url);
    return response;
  }

  protected void QtStage(PushOneCommit.Result c) throws Exception {
    RestResponse response = call_REST_API_Stage(c.getChangeId(), c.getCommit().getName());
    response.assertOK();
    Change change = c.getChange().change();
    assertStatusStaged(change);
    String branch = getBranchNameFromRef(change.getDest().branch());
    RevCommit stagingHead = getRemoteHead(project, R_STAGING + branch);
    assertReviewedByFooter(stagingHead, true);
    resetEvents();
  }

  protected RestResponse call_REST_API_Stage(String changeId, String revisionId) throws Exception {
    String url =
        "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~stage";
    RestResponse response = userRestSession.post(url);
    return response;
  }

  protected ChangeInfo cherryPick(final PushOneCommit.Result c, final String branch) throws Exception {
    // If the commit message does not specify a Change-Id, a new one is picked for the destination change.
    final CherryPickInput input = new CherryPickInput();
    input.message = "CHERRY" + c.getCommit().getFullMessage();
    input.destination = branch;

    final RestResponse response = call_REST_API_CherryPick(c.getChangeId(), c.getCommit().getName(), input);
    response.assertOK();
    return newGson().fromJson(response.getReader(), ChangeInfo.class);
  }

  protected RestResponse call_REST_API_CherryPick(
    final String changeId, final String revisionId, final CherryPickInput input)
        throws Exception {
    final String url = "/changes/" + changeId + "/revisions/" + revisionId + "/cherrypick";
    return userRestSession.post(url, input);
  }

  protected void QtUnStage(PushOneCommit.Result c) throws Exception {
    RestResponse response = call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c));
    response.assertOK();
    assertStatusNew(c.getChange().change());
  }

  protected RestResponse call_REST_API_UnStage(String changeId, String revisionId)
      throws Exception {
    String url =
        "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~unstage";
    RestResponse response = userRestSession.post(url);
    return response;
  }

  protected void QtNewBuild(String branch, String buildId) throws Exception {
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-new-build";
    commandStr += " --project " + project.get();
    commandStr += " --staging-branch " + branch;
    commandStr += " --build-id " + buildId;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();
    RevCommit buildHead = getRemoteHead(project, R_BUILDS + buildId);
    assertReviewedByFooter(buildHead, true);
    resetEvents();
  }

  protected void QtApproveBuild(String branch, String buildId) throws Exception {
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch " + branch;
    commandStr += " --build-id " + buildId;
    commandStr += " --result pass";
    commandStr += " --message " + BUILD_PASS_MESSAGE;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();
    RevCommit branchHead = getRemoteHead(project, R_HEADS + branch);
    assertReviewedByFooter(branchHead, true);
  }

  protected void QtFailBuild(String branch, String buildId) throws Exception {
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch " + branch;
    commandStr += " --build-id " + buildId;
    commandStr += " --result fail";
    commandStr += " --message " + BUILD_FAIL_MESSAGE;
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();
  }

  protected PushOneCommit.Result pushCommit(
      String branch, String message, String file, String content) throws Exception {
    String pushRef = R_PUSH + branch;
    PushOneCommit.Result c = createUserChange(pushRef, message, file, content);
    assertStatusNew(c.getChange().change());
    return c;
  }

  protected PushOneCommit.Result amendCommit(String changeId) throws Exception {
    String branch = "master";
    String pushRef = R_PUSH + branch;
    PushOneCommit push =
        pushFactory.create(
            user.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            CONTENT_DATA,
            changeId);
    return push.to(pushRef);
  }

  protected PushOneCommit.Result createUserChange(
      String ref, String message, String file, String content) throws Exception {
    PushOneCommit push = pushFactory.create(user.newIdent(), testRepo, message, file, content);
    PushOneCommit.Result result = push.to(ref);
    result.assertOkStatus();
    return result;
  }

  protected void assertCherryPick(RevCommit head, RevCommit source, RevCommit base) throws Exception {
    // Fetch all commit data
    Repository repo = repoManager.openRepository(project);
    RevWalk revWalk = new RevWalk(repo);
    source = revWalk.parseCommit(source);
    head = revWalk.parseCommit(head);

    assertThat(head).isNotEqualTo(source);
    assertThat(head.getName()).isNotEqualTo(source.getName());
    assertThat(head.getShortMessage()).isEqualTo(source.getShortMessage());
    assertThat(head.getFooterLines("Change-Id")).isEqualTo(source.getFooterLines("Change-Id"));
    assertThat(head.getParentCount()).isEqualTo(1);

    if (base != null) assertThat(head.getParent(0)).isEqualTo(base);
  }

  private void assertStatus(Change change, ChangeStatus status, boolean approved, boolean footer)
      throws Exception {
    ChangeInfo cf;

    if (approved) {
      cf =
          gApi.changes()
              .id(change.getChangeId())
              .get(DETAILED_LABELS, CURRENT_REVISION, CURRENT_COMMIT);
      Integer vote = getLabelValue(cf, "Code-Review", admin);
      assertWithMessage("label = Code-Review").that(vote).isEqualTo(2);
    } else {
      cf = gApi.changes().id(change.getChangeId()).get(CURRENT_REVISION, CURRENT_COMMIT);
    }

    assertThat(cf.status).isEqualTo(status);
    String commitMsg = cf.revisions.get(cf.currentRevision).commit.message;

    if (footer && cf.revisions.get(cf.currentRevision).commit.parents.size() == 1) {
      assertThat(commitMsg).contains("Reviewed-by");
    } else {
      assertThat(commitMsg).doesNotContain("Reviewed-by");
    }
  }

  protected void assertStatusNew(Change change) throws Exception {
    assertStatus(change, ChangeStatus.NEW, false, false);
  }

  protected void assertStatusStaged(Change change) throws Exception {
    assertStatus(change, ChangeStatus.STAGED, true, false);
  }

  protected void assertStatusIntegrating(Change change) throws Exception {
    assertStatus(change, ChangeStatus.INTEGRATING, true, false);
  }

  protected void assertStatusMerged(Change change) throws Exception {
    assertStatus(change, ChangeStatus.MERGED, true, true);
  }

  private Integer getLabelValue(ChangeInfo c, String label, TestAccount user) {
    Integer vote = 0;
    LabelInfo labels = c.labels.get(label);

    if (labels != null && labels.all != null) {
      for (ApprovalInfo approval : labels.all) {
        if (approval._accountId == user.id().get()) {
          vote = approval.value;
          break;
        }
      }
    }
    return vote;
  }

  protected void assertApproval(String changeId, TestAccount user) throws Exception {
    ChangeInfo c =
        gApi.changes().id(changeId).get(DETAILED_LABELS, CURRENT_REVISION, CURRENT_COMMIT);

    String label = "Code-Review";
    int expectedVote = 2;
    Integer vote = 0;

    vote = getLabelValue(c, label, user);
    assertWithMessage("label = " + label).that(vote).isEqualTo(expectedVote);
  }

  protected void assertReviewedByFooter(RevCommit commit, boolean exists) {

    // Skip initial commit and merge commits
    if (commit.getParentCount() != 1 || commit.equals(lastGeneratedCommit)) return;

    List<String> changeIds = commit.getFooterLines(FooterConstants.REVIEWED_BY);
    assertThat(!changeIds.isEmpty()).isEqualTo(exists);
  }

  protected void assertRefUpdatedEvents(String refName, RevCommit... expected) throws Exception {
    eventRecorder.assertRefUpdatedEvents(project.get(), refName, expected);
  }

  protected void resetEvents() {
    closeEventRecorder();
    startEventRecorder();
  }

  protected String getBranchNameFromRef(String refStr) {
    if (refStr.startsWith(R_HEADS)) {
      return refStr.substring(R_HEADS.length());
    } else {
      return refStr;
    }
  }

  private RevCommit getRefHead(Repository repo, String name) throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      Ref r = repo.exactRef(name);
      return r != null ? rw.parseCommit(r.getObjectId()) : null;
    }
  }

  protected RevCommit getRemoteRefHead(Project.NameKey project, String branch) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      return getRefHead(
          repo, branch.startsWith(Constants.R_REFS) ? branch : "refs/heads/" + branch);
    }
  }

  protected String getCurrentPatchSHA(PushOneCommit.Result c) throws Exception {
    return c.getChange().currentPatchSet().commitId().name();
  }

  protected List<RevCommit> getRemoteLog(String ref) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      rw.markStart(rw.parseCommit(repo.exactRef(ref).getObjectId()));
      return Lists.newArrayList(rw);
    }
  }

  protected String getCurrentPatchId(PushOneCommit.Result c) throws Exception {
    return String.valueOf(c.getChange().currentPatchSet().number());
  }

  // Restored from commit edb599b65eb412ed5f4c59372fa06135b0d8864c which inlined the methods.
  protected RevCommit getRemoteHead(Project.NameKey project, String branch) throws Exception {
    return projectOperations.project(project).getHead(branch);
  }

  protected RevCommit getRemoteHead() throws Exception {
    return getRemoteHead(project, "master");
  }

  protected RevCommit loadCommit(RevCommit commit) throws Exception {
      Repository repo = repoManager.openRepository(project);
      RevWalk revWalk = new RevWalk(repo);
      return revWalk.parseCommit(commit);
  }

}
