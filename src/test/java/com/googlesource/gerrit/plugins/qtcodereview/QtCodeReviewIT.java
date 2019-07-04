// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;

import com.google.common.collect.Lists;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.reviewdb.client.Change;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.LogManager;
import org.apache.log4j.PatternLayout;

import java.util.List;

import org.junit.Before;
import org.junit.Test;


@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

public class QtCodeReviewIT extends LightweightPluginDaemonTest {

    protected static final String R_HEADS = "refs/heads/";
    protected static final String R_STAGING = "refs/staging/";
    protected static final String R_BUILDS = "refs/builds/";
    protected static final String R_PUSH = "refs/for/";

    protected static final String CONTENT_DATA = "hereisjustsomecontentforthecommits";
    protected static final String BUILD_PASS_MESSAGE = "thebuildpassed";
    protected static final String BUILD_FAIL_MESSAGE = "thebuildfailed";

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

    @Test
    public void dummyTest() throws Exception {

    }

// Helper functions

    protected void QtDefer(PushOneCommit.Result c) throws Exception {
        RestResponse response = call_REST_API_Defer(c.getChangeId());
        response.assertOK();
        Change change = c.getChange().change();
        assertThat(change.getStatus()).isEqualTo(Change.Status.DEFERRED);
    }

    protected RestResponse call_REST_API_Defer(String changeId) throws Exception {
        String url = "/changes/"+changeId+"/gerrit-plugin-qt-workflow~defer";
        RestResponse response = userRestSession.post(url);
        return response;
    }

    protected void QtStage(PushOneCommit.Result c) throws Exception {
        RestResponse response = call_REST_API_Stage(c.getChangeId(), c.getCommit().getName());
        response.assertOK();
        Change change = c.getChange().change();
        assertStatusStaged(change);
        String branch = getBranchNameFromRef(change.getDest().get());
        RevCommit stagingHead = getRemoteHead(project, R_STAGING + branch);
        assertReviewedByFooter(stagingHead, true);
        resetEvents();
    }

    protected RestResponse call_REST_API_Stage(String changeId, String revisionId) throws Exception {
        String url = "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~stage";
        RestResponse response = userRestSession.post(url);
        return response;
    }

    protected void QtUnStage(PushOneCommit.Result c) throws Exception {
        RestResponse response = call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c));
        response.assertOK();
        assertStatusNew(c.getChange().change());
    }

    protected RestResponse call_REST_API_UnStage(String changeId, String revisionId) throws Exception {
        String url = "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~unstage";
        RestResponse response = userRestSession.post(url);
        return response;
    }

    protected void QtNewBuild(String branch, String buildId)  throws Exception {
        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-new-build";
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
        commandStr ="gerrit-plugin-qt-workflow staging-approve";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + branch;
        commandStr += " --build-id "+ buildId;
        commandStr += " --result pass";
        commandStr += " --message " + BUILD_PASS_MESSAGE;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNull();
        RevCommit branchHead = getRemoteHead(project, R_HEADS + branch);
        assertReviewedByFooter(branchHead, true);
    }

    protected void QtFailBuild(String branch, String buildId)  throws Exception {
        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-approve";
        commandStr += " --project " + project.get();
        commandStr += " --branch " + branch;
        commandStr += " --build-id "+ buildId;
        commandStr += " --result fail";
        commandStr += " --message " + BUILD_FAIL_MESSAGE;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNull();
    }

    protected PushOneCommit.Result pushCommit(String branch,
                                              String message,
                                              String file,
                                              String content)
                                              throws Exception {
        String pushRef = R_PUSH + branch;
        PushOneCommit.Result c = createUserChange(pushRef, message, file, content);
        assertStatusNew(c.getChange().change());
        return c;
    }

    protected PushOneCommit.Result amendCommit(String changeId) throws Exception {
        String branch = "master";
        String pushRef = R_PUSH + branch;
        PushOneCommit push = pushFactory.create(db,
                                                user.getIdent(),
                                                testRepo,
                                                PushOneCommit.SUBJECT,
                                                PushOneCommit.FILE_NAME,
                                                CONTENT_DATA,
                                                changeId);
        return push.to(pushRef);
    }

    protected PushOneCommit.Result createUserChange(String ref, String message, String file, String content) throws Exception {
        PushOneCommit push = pushFactory.create(db, user.getIdent(), testRepo, message, file, content);
        PushOneCommit.Result result = push.to(ref);
        result.assertOkStatus();
        return result;
    }

    protected void assertCherryPick(RevCommit head, RevCommit source, RevCommit base) {
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
            cf = gApi.changes().id(change.getChangeId()).get(DETAILED_LABELS, CURRENT_REVISION, CURRENT_COMMIT);
            Integer vote = getLabelValue(cf, "Code-Review", admin);
            assertThat(vote).named("label = Code-Review").isEqualTo(2);
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
                if (approval._accountId == user.id.get()) {
                    vote = approval.value;
                    break;
                }
            }
        }
        return vote;
    }

    protected void assertApproval(String changeId, TestAccount user) throws Exception {
        ChangeInfo c = gApi.changes().id(changeId).get(DETAILED_LABELS, CURRENT_REVISION, CURRENT_COMMIT);

        String label = "Code-Review";
        int expectedVote = 2;
        Integer vote = 0;

        vote = getLabelValue(c, label, user);
        assertThat(vote).named("label = " + label).isEqualTo(expectedVote);
    }

    protected void assertReviewedByFooter(RevCommit commit, boolean exists) {

        // Skip initial commit and merge commits
        if (commit.getParentCount() != 1) return;

        List<String> changeIds = commit.getFooterLines(FooterConstants.REVIEWED_BY);
        assertThat(!changeIds.isEmpty()).isEqualTo(exists);
    }

    protected void assertRefUpdatedEvents(String refName, RevCommit ... expected) throws Exception {
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

    protected List<RevCommit> getRemoteLog(String ref) throws Exception {
      try (Repository repo = repoManager.openRepository(project);
          RevWalk rw = new RevWalk(repo)) {
        rw.markStart(rw.parseCommit(repo.exactRef(ref).getObjectId()));
        return Lists.newArrayList(rw);
      }
    }

    protected String getCurrentPatchId(PushOneCommit.Result c) throws Exception {
        return String.valueOf(c.getChange().currentPatchSet().getPatchSetId());
    }

}
