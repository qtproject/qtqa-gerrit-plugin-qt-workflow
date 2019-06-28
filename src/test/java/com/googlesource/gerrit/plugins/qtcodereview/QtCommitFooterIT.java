// Copyright (C) 2019 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.COMMIT_FOOTERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.testing.Util.category;
import static com.google.gerrit.server.project.testing.Util.value;

import com.google.inject.Inject;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;

import com.google.gerrit.extensions.api.projects.ProjectInput;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.project.testing.Util;

import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule"
)

@UseSsh
public class QtCommitFooterIT extends LightweightPluginDaemonTest {

    @Inject private RequestScopeOperations requestScopeOperations;

    @Override
    protected void updateProjectInput(ProjectInput in) {
        // Reviewed-on and Tested-by footers are generated only in cherry pick mode
        in.submitType = SubmitType.CHERRY_PICK;
    }

    @Test
    public void removeCommitFooterLines() throws Exception {
        LabelType sanity = category("Sanity-Review", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
        LabelType verified = category("Verified", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
        LabelType changelog = category("ChangeLog", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
        try (ProjectConfigUpdate u = updateProject(project)) {
            u.getConfig().getLabelSections().put(sanity.getName(), sanity);
            u.getConfig().getLabelSections().put(verified.getName(), verified);
            u.getConfig().getLabelSections().put(changelog.getName(), changelog);
            AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
            String heads = "refs/heads/*";
            Util.allow(u.getConfig(), Permission.forLabel("Sanity-Review"), -1, 1, registered, heads);
            Util.allow(u.getConfig(), Permission.forLabel("Code-Review"), -2, +2, registered, heads);
            Util.allow(u.getConfig(), Permission.forLabel("Verified"), -1, +1, registered, heads);
            Util.allow(u.getConfig(), Permission.forLabel("ChangeLog"), -1, +1, registered, heads);
            u.save();
        }

        PushOneCommit.Result change = createChange();
        requestScopeOperations.setApiUser(user.id());
        ReviewInput input = new ReviewInput();
        input.label("Code-Review", 2);
        input.label(verified.getName(), 1);
        input.label(sanity.getName(), 1);
        input.label(changelog.getName(), 1);
        gApi.changes().id(change.getChangeId()).current().review(input);

        requestScopeOperations.setApiUser(admin.id());
        gApi.changes().id(change.getChangeId()).current().submit();

        ChangeInfo cf = gApi.changes().id(change.getChangeId()).get(ALL_REVISIONS, COMMIT_FOOTERS);
        String commitMsg = cf.revisions.get(cf.currentRevision).commitWithFooters;
        assertThat(commitMsg).contains("Reviewed-by");
        assertThat(commitMsg).doesNotContain("Reviewed-on");
        assertThat(commitMsg).doesNotContain("Sanity-Review");
        assertThat(commitMsg).doesNotContain("Tested-by");
        assertThat(commitMsg).doesNotContain("ChangeLog");
    }
}
