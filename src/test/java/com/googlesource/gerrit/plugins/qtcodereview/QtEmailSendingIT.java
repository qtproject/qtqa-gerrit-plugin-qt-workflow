//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.mail.Address;
import com.google.gerrit.mail.EmailHeader;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.testing.Util;
import com.google.gerrit.testing.FakeEmailSender;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtEmailSendingIT extends QtCodeReviewIT {

    @Before
    public void grantPermissions() throws Exception {
        grant(project, "refs/heads/master", Permission.QT_STAGE, false, REGISTERED_USERS);
        grant(project, "refs/staging/*", Permission.PUSH, false, adminGroupUuid());
        grant(project, "refs/builds/*", Permission.CREATE, false, adminGroupUuid());

        ProjectConfig cfg = projectCache.get(project).getConfig();
        Util.allow(cfg, Permission.forLabel("Code-Review"), -2, +2, REGISTERED_USERS, "refs/*");
    }

    @Test
    public void stagedByOwnerBuildPass() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);
        QtNewBuild("master", "test_build_01");
        sender.clear();
        QtApproveBuild("master", "test_build_01");

        FakeEmailSender.Message m = sender.getMessages(c.getChangeId(), "merged").get(0);
        Address expectedTo = new Address(user.fullName, user.email);
        assertThat(m.rcpt()).containsExactly(expectedTo);
        assertThat(((EmailHeader.AddressList) m.headers().get("To")).getAddressList())
          .containsExactly(expectedTo);
        assertThat(m.body()).contains(c.getChangeId());
    }

    @Test
    public void stagedByOwnerBuildFail() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStage(c);
        QtNewBuild("master", "test_build_02");
        sender.clear();
        QtFailBuild("master", "test_build_02");

        FakeEmailSender.Message m = sender.getMessages(c.getChangeId(), "comment").get(0);
        Address expectedTo = new Address(user.fullName, user.email);
        assertThat(m.rcpt()).containsExactly(expectedTo);
        assertThat(((EmailHeader.AddressList) m.headers().get("To")).getAddressList())
            .containsExactly(expectedTo);
        assertThat(m.body()).contains(c.getChangeId());
        assertThat(m.body()).contains(BUILD_FAIL_MESSAGE);
    }

    @Test
    public void stagedByOtherBuildPass() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStageByAdmin(c);
        QtNewBuild("master", "test_build_03");
        sender.clear();
        QtApproveBuild("master", "test_build_03");

        FakeEmailSender.Message m = sender.getMessages(c.getChangeId(), "merged").get(0);
        Address expectedTo = new Address(user.fullName, user.email);
        assertThat(m.rcpt()).containsExactly(expectedTo);
        assertThat(((EmailHeader.AddressList) m.headers().get("To")).getAddressList())
            .containsExactly(expectedTo);
        assertThat(m.body()).contains(c.getChangeId());
    }

    @Test
    public void stagedByOtherBuildFail() throws Exception {
        PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
        approve(c.getChangeId());
        QtStageByAdmin(c);
        QtNewBuild("master", "test_build_04");
        sender.clear();
        QtFailBuild("master", "test_build_04");

        FakeEmailSender.Message m = sender.getMessages(c.getChangeId(), "comment").get(0);
        Address expectedTo = new Address(user.fullName, user.email);
        assertThat(m.rcpt()).containsExactly(expectedTo);
        assertThat(((EmailHeader.AddressList) m.headers().get("To")).getAddressList())
            .containsExactly(expectedTo);
        assertThat(m.body()).contains(c.getChangeId());
        assertThat(m.body()).contains(BUILD_FAIL_MESSAGE);
    }

    private void QtStageByAdmin(PushOneCommit.Result c) throws Exception {
        RestResponse response = call_REST_API_Stage_By_Admin(c.getChangeId(), c.getCommit().getName());
        response.assertOK();
        assertStatusStaged(c.getChange().change());
    }

    private RestResponse call_REST_API_Stage_By_Admin(String changeId, String revisionId) throws Exception {
        String url = "/changes/" + changeId + "/revisions/" + revisionId + "/gerrit-plugin-qt-workflow~stage";
        RestResponse response = adminRestSession.post(url);
        return response;
    }

}
