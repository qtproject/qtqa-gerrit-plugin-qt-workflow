// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;

import org.apache.http.HttpStatus;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtUnStageIT extends QtCodeReviewIT {

    private final String UNSTAGED_MSG = "Unstaged";

    @Before
    public void SetDefaultPermissions() throws Exception {
        createBranch(new Branch.NameKey(project, "feature"));
        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
    }

    @Test
    public void singleChange_UnStage() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        RevCommit stagingHead = qtUnStageExpectCommit(c, initialHead);
        assertApproval(c.getChangeId(), admin);
    }

    @Test
    public void multiChange_UnStage_NoFastForwardOnStagingRefRebuild() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c1.getChangeId());
        QtStage(c1);

        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        approve(c2.getChangeId());
        QtStage(c2);

        RevCommit stagingHead = qtUnStageExpectCherryPick(c1, c2);
    }

    @Test
    public void multiChange_UnStage_First() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

        approve(c1.getChangeId());
        QtStage(c1);
        approve(c2.getChangeId());
        QtStage(c2);
        approve(c3.getChangeId());
        QtStage(c3);

        RevCommit stagingHead = qtUnStageExpectCherryPick(c1, c3);
        assertStatusStaged(c2.getChange().change());
        assertStatusStaged(c3.getChange().change());
    }

    @Test
    public void multiChange_UnStage_Middle() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

        approve(c1.getChangeId());
        QtStage(c1);
        approve(c2.getChangeId());
        QtStage(c2);
        approve(c3.getChangeId());
        QtStage(c3);

        RevCommit stagingHead = qtUnStageExpectCherryPick(c2, c3);
        assertStatusStaged(c1.getChange().change());
        assertStatusStaged(c3.getChange().change());
    }

    @Test
    public void multiChange_UnStage_Last() throws Exception {
        // Push 3 independent commits
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

        approve(c1.getChangeId());
        QtStage(c1);
        approve(c2.getChangeId());
        QtStage(c2);
        RevCommit expectedFastForward = getRemoteHead(project, R_STAGING + "master");
        approve(c3.getChangeId());
        QtStage(c3);

        RevCommit stagingHead = qtUnStageExpectCommit(c3, expectedFastForward);
        assertStatusStaged(c1.getChange().change());
        assertStatusStaged(c2.getChange().change());
    }

    @Test
    public void multiChange_UnStage_Merge_When_First() throws Exception {
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
        RevCommit stagingHead = getRemoteHead(project, R_HEADS + "master");

        // merge feature branch into master
        PushOneCommit mm = pushFactory.create(db, admin.getIdent(), testRepo);
        mm.setParents(ImmutableList.of(c1.getCommit(), f2.getCommit()));
        PushOneCommit.Result m = mm.to("refs/for/master");
        m.assertOkStatus();
        approve(m.getChangeId());
        QtStage(m);

        // Stage another change
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg3", "file3", "content3");
        approve(c2.getChangeId());
        QtStage(c2);

        stagingHead = qtUnStageExpectCherryPick(m, c2, stagingHead);
        String gitLog = getRemoteLog("refs/staging/master").toString();
        assertThat(gitLog).contains(initialHead.getId().name());
        assertThat(gitLog).contains(c1.getCommit().getId().name());
        assertThat(gitLog).contains(stagingHead.getId().name());
        assertThat(gitLog).doesNotContain(m.getCommit().getId().name());
        assertThat(gitLog).doesNotContain(f1.getCommit().getId().name());
        assertThat(gitLog).doesNotContain(f2.getCommit().getId().name());
    }

    @Test
    public void multiChange_UnStage_Before_MergeCommit_ExpectFastForward() throws Exception {
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

        // Stage a change
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg4", "file4", "content4");
        approve(c2.getChangeId());
        QtStage(c2);

        // merge feature branch and stage it on top
        PushOneCommit mm = pushFactory.create(db, admin.getIdent(), testRepo);
        mm.setParents(ImmutableList.of(c1.getCommit(), f2.getCommit()));
        PushOneCommit.Result m = mm.to("refs/for/master");
        m.assertOkStatus();
        RevCommit originalMergeCommit = m.getCommit();
        approve(m.getChangeId());
        QtStage(m);

        RevCommit stagingHead = qtUnStageExpectMergeFastForward(c2, originalMergeCommit);
        String gitLog = getRemoteLog("refs/staging/master").toString();
        assertThat(gitLog).contains(initialHead.getId().name());
        assertThat(gitLog).contains(c1.getCommit().getId().name());
        assertThat(gitLog).contains(stagingHead.getId().name());
        assertThat(gitLog).contains(f1.getCommit().getId().name());
        assertThat(gitLog).contains(f2.getCommit().getId().name());
        assertThat(gitLog).contains(m.getCommit().getId().name());
        assertThat(gitLog).doesNotContain(c2.getCommit().getId().name());
    }

    @Test
    public void multiChange_UnStage_Before_MergeCommit_ExpectMergeOfMerge() throws Exception {
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

        // Stage a change
        testRepo.reset(initialHead);
        PushOneCommit.Result c2 = pushCommit("master", "commitmsg4", "file4", "content4");
        approve(c2.getChangeId());
        QtStage(c2);

        // merge feature branch and stage it on top
        PushOneCommit mm = pushFactory.create(db, admin.getIdent(), testRepo);
        mm.setParents(ImmutableList.of(f2.getCommit(),c1.getCommit()));
        PushOneCommit.Result m = mm.to("refs/for/master");
        m.assertOkStatus();
        approve(m.getChangeId());
        QtStage(m);

        RevCommit stagingHead = qtUnStageExpectMergeOfMerge(c2, m);
        String gitLog = getRemoteLog("refs/staging/master").toString();
        assertThat(gitLog).contains(initialHead.getId().name());
        assertThat(gitLog).contains(c1.getCommit().getId().name());
        assertThat(gitLog).contains(stagingHead.getId().name());
        assertThat(gitLog).contains(f1.getCommit().getId().name());
        assertThat(gitLog).contains(f2.getCommit().getId().name());
        assertThat(gitLog).contains(m.getCommit().getId().name());
        assertThat(gitLog).doesNotContain(c2.getCommit().getId().name());
    }

    @Test
    public void multiChange_UnStage_MergeConflict() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "thesamefile", "content1");
        // c2 depends on c1
        PushOneCommit.Result c2 = pushCommit("master", "commitdependingon1", "thesamefile", "conflict");
        testRepo.reset(initialHead);
        PushOneCommit.Result c3 = pushCommit("master", "commitmsg3", "file3", "content3");

        approve(c1.getChangeId());
        QtStage(c1);
        approve(c2.getChangeId());
        QtStage(c2);
        approve(c3.getChangeId());
        QtStage(c3);

        RevCommit stagingHead = qtUnStageExpectCommit(c1, initialHead);
        assertStatusNew(c2.getChange().change());
        assertStatusNew(c3.getChange().change());
    }

    @Test
    public void errorUnStage_No_Permission() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

         deny(project, "refs/heads/master", Permission.QT_STAGE, REGISTERED_USERS);

        RestResponse response = qtUnStageExpectFail(c, HttpStatus.SC_FORBIDDEN);
        assertThat(response.getEntityContent()).contains("not permitted");

        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
    }

    @Test
    public void errorUnStage_Wrong_Status() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());

        RestResponse response = qtUnStageExpectFail(c, HttpStatus.SC_CONFLICT);
        assertThat(response.getEntityContent()).isEqualTo("change is NEW");
    }

    @Test
    public void errorUnStage_Invalid_ChangeId() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        RestResponse response = call_REST_API_UnStage("thischangeidnotfound", c.getCommit().getName());
        response.assertStatus(HttpStatus.SC_NOT_FOUND);
        assertThat(response.getEntityContent()).contains("Not found: thischangeidnotfound");
    }

    @Test
    public void errorUnStage_Invalid_RevisionId() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        RestResponse response = call_REST_API_UnStage(c.getChangeId(), "thisrevisionidnotfound");
        response.assertStatus(HttpStatus.SC_NOT_FOUND);
        assertThat(response.getEntityContent()).contains("Not found: thisrevisionidnotfound");
    }

    @Test
    public void errorUnStage_Revision_Not_Current() throws Exception {

        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        PushOneCommit.Result c2 = amendCommit(c1.getChangeId());
        approve(c2.getChangeId());
        QtStage(c2);

        RestResponse response = call_REST_API_UnStage(c1.getChangeId(), c1.getCommit().getName());
        response.assertStatus(HttpStatus.SC_CONFLICT);
        assertThat(response.getEntityContent()).contains("is not current revision");
    }

    private RevCommit qtUnStageExpectCherryPick(PushOneCommit.Result c,
                                                PushOneCommit.Result expectedContent)
                                                throws Exception {
        return qtUnStage(c, null, expectedContent, null, false, false);
    }

    private RevCommit qtUnStageExpectCherryPick(PushOneCommit.Result c,
                                                PushOneCommit.Result expectedContent,
                                                RevCommit base)
                                                throws Exception {
        return qtUnStage(c, null, expectedContent, base, false, false);
    }

    private RevCommit qtUnStageExpectCommit(PushOneCommit.Result c,
                                           RevCommit expectedStagingHead)
                                           throws Exception {
        return qtUnStage(c, expectedStagingHead, null, null, false, false);
    }

    private RevCommit qtUnStageExpectMergeFastForward(PushOneCommit.Result c,
                                                      RevCommit expectedStagingHead)
                                                      throws Exception {
        return qtUnStage(c, expectedStagingHead, null, null, true, true);
    }

    private RevCommit qtUnStageExpectMergeOfMerge(PushOneCommit.Result c,
                                                  PushOneCommit.Result expectedContent)
                                                  throws Exception {
        return qtUnStage(c, null, expectedContent, null, true, false);
    }

    private RevCommit qtUnStage(PushOneCommit.Result c,
                                RevCommit expectedStagingHead,
                                PushOneCommit.Result expectedContent,
                                RevCommit base,
                                boolean merge,
                                boolean fastForward)
                                throws Exception {
        String branch = getBranchNameFromRef(c.getChange().change().getDest().get());
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;
        RevCommit originalCommit = c.getCommit();
        String changeId = c.getChangeId();
        RevCommit initialHead = getRemoteHead(project, branchRef);
        RevCommit oldStagingHead = getRemoteHead(project, stagingRef);

        RestResponse response = call_REST_API_UnStage(changeId, getCurrentPatchId(c));
        response.assertOK();

        RevCommit masterHead = getRemoteHead(project, branchRef);
        assertThat(masterHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        assertThat(stagingHead).isNotEqualTo(oldStagingHead);
        assertReviewedByFooter(stagingHead, true);

        if (fastForward) {
            assertThat(stagingHead).isEqualTo(expectedStagingHead);
        } else if (merge) {
            assertThat(stagingHead.getParentCount()).isEqualTo(2);
            assertThat(stagingHead.getParent(1)).isEqualTo(expectedContent.getCommit());
            expectedStagingHead = stagingHead;
        } else if (expectedStagingHead == null && expectedContent != null) {
            assertCherryPick(stagingHead, expectedContent.getCommit(), base);
            expectedStagingHead = stagingHead;
        } else {
            assertThat(stagingHead).isEqualTo(expectedStagingHead); // staging is updated
        }

        if (expectedStagingHead.equals(oldStagingHead)) {
            assertRefUpdatedEvents(stagingRef); // no events
        } else {
            assertRefUpdatedEvents(stagingRef, oldStagingHead, expectedStagingHead);
            resetEvents();
        }

        assertStatusNew(c.getChange().change());

        ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
        assertThat(messages.get(messages.size() - 1).getMessage()).isEqualTo(UNSTAGED_MSG);

        return stagingHead;
    }

    private RestResponse qtUnStageExpectFail(PushOneCommit.Result c,
                                             int expectedStatus)
                                             throws Exception {
        String branch = getBranchNameFromRef(c.getChange().change().getDest().get());
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;

        RevCommit initialHead = getRemoteHead(project, branchRef);
        RevCommit oldStagingHead = getRemoteHead(project, stagingRef);

        RestResponse response = call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c));
        response.assertStatus(expectedStatus);

        RevCommit masterHead = getRemoteHead(project, branchRef);
        assertThat(masterHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        if (stagingHead != null) assertThat(stagingHead.getId()).isEqualTo(oldStagingHead.getId()); // staging is not updated

        assertRefUpdatedEvents(branchRef); // no events
        assertRefUpdatedEvents(stagingRef); // no events

        return response;
    }

}
