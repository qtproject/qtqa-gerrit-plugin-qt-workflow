// Copyright (C) 2019-23 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.testing.TestLabels.label;
import static com.google.gerrit.server.project.testing.TestLabels.value;

import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.projects.ProjectInput;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.server.project.testing.TestLabels;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtCommitFooterIT extends QtCodeReviewIT {
  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;

  @Override
  protected void updateProjectInput(ProjectInput in) {
    // Reviewed-on and Tested-by footers are generated only in cherry pick mode
    in.submitType = SubmitType.CHERRY_PICK;
  }

  @Test
  public void removeCommitFooterLines() throws Exception {
    LabelType sanity =
        label("Sanity-Review", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    LabelType apireview =
        label("API-Review", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"), value(-2, "Block"));
    LabelType verified =
        label("Verified", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    LabelType changelog =
        label("ChangeLog", value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getLabelSections().put(sanity.getName(), sanity);
      u.getConfig().getLabelSections().put(apireview.getName(), apireview);
      u.getConfig().getLabelSections().put(verified.getName(), verified);
      u.getConfig().getLabelSections().put(changelog.getName(), changelog);
      u.save();
    }
    AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = "refs/heads/*";

    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(sanity.getName()).ref(heads).group(registered).range(-1, 1))
        .add(allowLabel(apireview.getName()).ref(heads).group(registered).range(-2, 1))
        .add(
            allowLabel(TestLabels.codeReview().getName()).ref(heads).group(registered).range(-2, 2))
        .add(allowLabel(verified.getName()).ref(heads).group(registered).range(-1, 1))
        .add(allowLabel(changelog.getName()).ref(heads).group(registered).range(-1, 1))
        .update();

    PushOneCommit.Result change =
        createChange(
            "ChangeLog: First line, this should stay\n\nDetailed description\n",
            "testfile.txt",
            "dummytext");
    requestScopeOperations.setApiUser(user.id());
    ReviewInput input = new ReviewInput();
    input.label("Code-Review", 2);
    input.label(verified.getName(), 1);
    input.label(sanity.getName(), 1);
    input.label(apireview.getName(), 1);
    input.label(changelog.getName(), 1);
    gApi.changes().id(change.getChangeId()).current().review(input);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(change.getChangeId()).current().submit();

    ChangeInfo cf = gApi.changes().id(change.getChangeId()).get(CURRENT_REVISION, CURRENT_COMMIT);
    String commitMsg = cf.revisions.get(cf.currentRevision).commit.message;
    String[] splitCommit =
        commitMsg.split("\n", 2); // Subject line and the rest of commit separated
    assertThat(splitCommit[0]).contains("ChangeLog");
    assertThat(splitCommit[1]).contains("Reviewed-by");
    assertThat(splitCommit[1]).doesNotContain("Reviewed-on");
    assertThat(splitCommit[1]).doesNotContain("Sanity-Review");
    assertThat(splitCommit[1]).doesNotContain("API-Review");
    assertThat(splitCommit[1]).doesNotContain("Tested-by");
    assertThat(splitCommit[1]).doesNotContain("ChangeLog");
  }

  @Test
  public void removeCommitFooterLinesKeepReviewedOn() throws Exception {

    RevCommit initialHead = getRemoteHead();

    // Change setting for the project to show 'Reviewed-on' commit message footer
    Config cfg = new Config();
    cfg.fromText(projectOperations.project(project).getConfig().toText());
    cfg.setBoolean("plugin", "gerrit-plugin-qt-workflow", "showReviewedOnFooter", true);
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":" + RefNames.REFS_CONFIG);
    testRepo.reset(RefNames.REFS_CONFIG);
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, "Subject", "project.config", cfg.toText())
            .to(RefNames.REFS_CONFIG);
    r.assertOkStatus();

    AccountGroup.UUID registered = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();

    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(TestLabels.codeReview().getName())
                .ref("refs/heads/*")
                .group(registered)
                .range(-2, 2))
        .update();

    testRepo.reset(initialHead);
    PushOneCommit.Result change = createChange();
    requestScopeOperations.setApiUser(user.id());
    ReviewInput input = new ReviewInput();
    input.label("Code-Review", 2);
    gApi.changes().id(change.getChangeId()).current().review(input);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(change.getChangeId()).current().submit();

    ChangeInfo cf = gApi.changes().id(change.getChangeId()).get(CURRENT_REVISION, CURRENT_COMMIT);
    String commitMsg = cf.revisions.get(cf.currentRevision).commit.message;
    assertThat(commitMsg).contains("Reviewed-by");
    assertThat(commitMsg).contains("Reviewed-on");
  }
}
