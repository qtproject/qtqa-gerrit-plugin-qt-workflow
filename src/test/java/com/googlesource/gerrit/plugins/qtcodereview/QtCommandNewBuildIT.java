// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;

import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;

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
public class QtCommandNewBuildIT extends QtCodeReviewIT {

    private final String BUILDING_MSG = "Added to build ";

    @Before
    public void SetDefaultPermissions() throws Exception {
        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
        grant(project, "refs/staging/*", Permission.PUSH, false, adminGroupUuid());
        grant(project, "refs/builds/*", Permission.CREATE, false, adminGroupUuid());
    }

    @Test
    public void singleChange_New_Staged_Integrating() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        RevCommit buildHead = qtNewBuild("master", "test-build-100", c, null);
    }

    @Test
    public void multiChange_New_Staged_Integrating() throws Exception {
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

        RevCommit buildHead = qtNewBuild("master", "test-build-101", c3, null);
        assertStatusIntegrating(c1.getChange().change());
        assertStatusIntegrating(c2.getChange().change());
    }


    @Test
    public void errorNewBuild_NoPermission() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-new-build";
        commandStr += " --project " + project.get();
        commandStr += " --staging-branch master";
        commandStr += " --build-id test-build-500";
        String resultStr = userSshSession.exec(commandStr);
        assertThat(userSshSession.getError()).contains("Authentication failed to access repository");
    }

    @Test
    public void errorNewBuild_RepoNotFound() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String commandStr;
        commandStr ="gerrit-plugin-qt-workflow staging-new-build";
        commandStr += " --project notarepo";
        commandStr += " --staging-branch master";
        commandStr += " --build-id test-build-500";
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).contains("project not found");
    }

    @Test
    public void errorNewBuild_BuildAlreadyExists() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        RevCommit buildHead = qtNewBuild("master", "test-build-501", c, null);
        String resultStr = qtNewBuildExpectFail("master", "test-build-501");
        assertThat(resultStr).contains("Target build already exists!");
    }

    @Test
    public void errorNewBuild_NoChanges() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);
        QtUnStage(c);

        String resultStr = qtNewBuildExpectFail("master", "test-build-502");
        assertThat(resultStr).contains("No changes in staging branch. Not creating a build reference");
    }

    @Test
    public void errorNewBuild_NoStagingRef() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");

        String resultStr = qtNewBuildExpectFail("master", "test-build-503");
        assertThat(resultStr).contains("Staging ref not found!");
    }

    @Test
    public void errorNewBuild_NonExistingBranch() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);

        String resultStr = qtNewBuildExpectFail("invalidbranchname", "test-build-504");
        assertThat(resultStr).contains("Staging ref not found!");
    }

    @Test
    public void errorAmend_Status_Integrating() throws Exception {
        RevCommit initialHead = getRemoteHead();
        PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c1.getChangeId());
        QtStage(c1);
        RevCommit buildHead = qtNewBuild("master", "test-build-150", c1, null);

        PushOneCommit.Result c2 = amendCommit(c1.getChangeId());
        c2.assertErrorStatus(" closed");

        RevCommit updatedHead = getRemoteHead(project, "master");
        assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // not updated
    }

    private RevCommit qtNewBuild(String branch,
                                 String buildId,
                                 PushOneCommit.Result c,
                                 RevCommit expectedHead)
                                 throws Exception {
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;
        String buildRef = R_BUILDS + buildId;
        String commandStr;
        RevCommit initialHead = getRemoteHead(project, branchRef);

        commandStr ="gerrit-plugin-qt-workflow staging-new-build";
        commandStr += " --project " + project.get();
        commandStr += " --staging-branch " + branch;
        commandStr += " --build-id " + buildId;
        String resultStr = adminSshSession.exec(commandStr);
        assertThat(adminSshSession.getError()).isNull();

        RevCommit updatedHead = getRemoteHead(project, branchRef);
        assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        assertThat(stagingHead.getId()).isNotEqualTo(initialHead.getId()); // staging is not master

        if (expectedHead == null) {
             assertCherryPick(stagingHead, c.getCommit(), null);
             expectedHead = stagingHead;
        }

        RevCommit buildHead = getRemoteHead(project, buildRef);
        assertThat(buildHead).isEqualTo(expectedHead); // build ref is updated
        assertRefUpdatedEvents(buildRef, null, expectedHead);
        assertReviewedByFooter(buildHead, true);

        resetEvents();

        assertStatusIntegrating(c.getChange().change());

        ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
        assertThat(messages.get(messages.size() - 1).getMessage()).contains(BUILDING_MSG + buildId); // check last message

        return buildHead;
    }

    private String qtNewBuildExpectFail(String branch,
                                        String buildId)
                                        throws Exception {
        String stagingRef = R_STAGING + branch;
        String branchRef = R_HEADS + branch;
        String commandStr;
        RevCommit initialHead = getRemoteHead(project, branchRef);
        RevCommit stagingHeadOld = getRemoteHead(project, stagingRef);

        commandStr ="gerrit-plugin-qt-workflow staging-new-build";
        commandStr += " --project " + project.get();
        commandStr += " --staging-branch " + branch;
        commandStr += " --build-id " + buildId;
        String resultStr = adminSshSession.exec(commandStr);

        RevCommit updatedHead = getRemoteHead(project, branchRef);
        if (updatedHead != null) assertThat(updatedHead.getId()).isEqualTo(initialHead.getId()); // master is not updated

        RevCommit stagingHead = getRemoteHead(project, stagingRef);
        if (stagingHeadOld != null && stagingHead != null) assertThat(stagingHead.getId()).isEqualTo(stagingHeadOld.getId()); // staging is not updated

        return adminSshSession.getError();
    }

}
