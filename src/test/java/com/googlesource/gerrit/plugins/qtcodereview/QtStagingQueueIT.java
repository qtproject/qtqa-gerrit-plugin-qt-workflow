// Copyright (C) 2026 The Qt Company

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.permissions.DefaultPermissionMappings.pluginCapabilityName;

import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate;
import com.google.gerrit.entities.ChangeMessage;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.access.PluginPermission;
import java.util.ArrayList;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "gerrit-plugin-qt-workflow",
    sysModule = "com.googlesource.gerrit.plugins.qtcodereview.QtModule",
    sshModule = "com.googlesource.gerrit.plugins.qtcodereview.QtSshModule")
@UseSsh
public class QtStagingQueueIT extends QtCodeReviewIT {
  private static final String PRESTAGE_MSG =
      "Assigned to staging queue, waiting for promotion to CI";
  private static final String STAGED_MSG = "Staged for CI, waiting for next integration";

  @Before
  public void SetDefaultPermissions() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            TestProjectUpdate.allow(Permission.QT_STAGE)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();
    projectOperations
        .project(project)
        .forUpdate()
        .add(TestProjectUpdate.allow(Permission.PUSH).ref("refs/staging/*").group(adminGroupUuid()))
        .update();
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            TestProjectUpdate.allow(Permission.CREATE).ref("refs/builds/*").group(adminGroupUuid()))
        .update();
    clearPrestageConfig();
  }

  private void clearPrestageConfig() throws Exception {
    setPrestageOnBranches();
  }

  private void setPrestageOnBranches(String... branches) throws Exception {
    RevCommit initialHead = getRemoteHead();
    Config cfg = new Config();
    cfg.fromText(projectOperations.project(project).getConfig().toText());
    if (branches.length > 0) {
      cfg.setString(
          "plugin",
          "gerrit-plugin-qt-workflow",
          "stagingQueueBranches",
          String.join(" ", branches));
    } else {
      cfg.unset("plugin", "gerrit-plugin-qt-workflow", "stagingQueueBranches");
    }
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":" + RefNames.REFS_CONFIG);
    testRepo.reset(RefNames.REFS_CONFIG);
    PushOneCommit.Result r =
        pushFactory
            .create(
                admin.newIdent(),
                testRepo,
                "Update prestage config",
                "project.config",
                cfg.toText())
            .to(RefNames.REFS_CONFIG);
    r.assertOkStatus();
    testRepo.reset(initialHead);
  }

  private void grantStagingPromoteCapability() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(
            allowCapability(
                    pluginCapabilityName(
                        new PluginPermission(
                            "gerrit-plugin-qt-workflow",
                            QtStagingPromoteCapability.STAGING_PROMOTE)))
                .group(REGISTERED_USERS))
        .update();
  }

  @Test
  public void singleChange_PreStage() throws Exception {
    setPrestageOnBranches("master");
    RevCommit initialHead = getRemoteHead();
    RevCommit initialStagingHead = getRemoteRefHead(project, R_STAGING + "master");
    if (initialStagingHead == null) initialStagingHead = initialHead;
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    RestResponse response = call_REST_API_Stage(c.getChangeId(), c.getCommit().getName());
    response.assertOK();

    assertThat(getRemoteHead().getId()).isEqualTo(initialHead.getId()); // branch not updated
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isEqualTo(initialStagingHead.getId()); // not cherry-picked onto staging branch
    assertStatusPrestaged(c.getChange().change());

    ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage()).contains(PRESTAGE_MSG);
  }

  @Test
  public void singleChange_NonPreStageBranch_Stage() throws Exception {
    // stagingQueueBranches not configured for the branch — normal staging must be unchanged
    setPrestageOnBranches("6.11"); // intentional to test that no effect master branch
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    QtStage(c); // The function also verifies the status change to staged
  }

  @Test
  public void singleChange_PreStage_ThenUnStage_BackToNew() throws Exception {
    setPrestageOnBranches("master");
    RevCommit initialHead = getRemoteHead();
    RevCommit initialStagingHead = getRemoteRefHead(project, R_STAGING + "master");
    if (initialStagingHead == null) initialStagingHead = initialHead;
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusPrestaged(c.getChange().change());

    call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusNew(c.getChange().change());

    // Staging ref must not have had the change cherry-picked onto it
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isEqualTo(initialStagingHead.getId());
  }

  @Test
  public void errorPreStage_NoPermission() throws Exception {
    setPrestageOnBranches("master");
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            TestProjectUpdate.deny(Permission.QT_STAGE)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();

    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    RestResponse response = call_REST_API_Stage(c.getChangeId(), c.getCommit().getName());
    response.assertStatus(HttpStatus.SC_FORBIDDEN);

    projectOperations
        .project(project)
        .forUpdate()
        .add(
            TestProjectUpdate.allow(Permission.QT_STAGE)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();
  }

  @Test
  public void errorPromote_NoCapability() throws Exception {
    setPrestageOnBranches("master");
    // stagingPromote capability intentionally NOT granted
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusPrestaged(c.getChange().change());

    RestResponse response = call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c));
    response.assertStatus(HttpStatus.SC_FORBIDDEN);
    assertThat(response.getEntityContent()).contains("stagingPromote");

    assertStatusPrestaged(c.getChange().change()); // status unchanged
  }

  @Test
  public void singleChange_PreStage_ThenPromote() throws Exception {
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    RevCommit initialHead = getRemoteHead();
    RevCommit initialStagingHead = getRemoteRefHead(project, R_STAGING + "master");
    if (initialStagingHead == null) initialStagingHead = initialHead;
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    // Stage → PRESTAGED, staging ref not cherry-picked
    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusPrestaged(c.getChange().change());
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isEqualTo(initialStagingHead.getId());

    // Promote → STAGED, staging ref updated
    call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusStaged(c.getChange().change());
    RevCommit stagingHead = getRemoteRefHead(project, R_STAGING + "master");
    assertThat(stagingHead).isNotNull();
    assertThat(stagingHead.getId()).isNotEqualTo(initialStagingHead.getId());
    assertReviewedByFooter(stagingHead, true);

    ArrayList<ChangeMessage> messages = new ArrayList(c.getChange().messages());
    assertThat(messages.get(messages.size() - 1).getMessage()).isEqualTo(STAGED_MSG);
  }

  @Test
  public void singleChange_Staged_InPreStageMode_UnStage_BackToPrestaged() throws Exception {
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    // Stage → PRESTAGED, promote → STAGED
    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusStaged(c.getChange().change());
    RevCommit stagingHeadAfterPromote = getRemoteRefHead(project, R_STAGING + "master");
    assertThat(stagingHeadAfterPromote).isNotNull();

    // Unstage STAGED in prestage mode → PRESTAGED (not NEW), staging ref rebuilt
    call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusPrestaged(c.getChange().change());

    RevCommit stagingHeadAfterUnstage = getRemoteRefHead(project, R_STAGING + "master");
    assertThat(stagingHeadAfterUnstage).isNotNull();
    assertThat(stagingHeadAfterUnstage.getId()).isNotEqualTo(stagingHeadAfterPromote.getId());
  }

  @Test
  public void multiChange_PreStage_ThenPromoteAll() throws Exception {
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    RevCommit initialHead = getRemoteHead();
    RevCommit initialStagingHead = getRemoteRefHead(project, R_STAGING + "master");
    if (initialStagingHead == null) initialStagingHead = initialHead;

    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    // Both changes go to PRESTAGED, staging ref not cherry-picked
    call_REST_API_Stage(c1.getChangeId(), c1.getCommit().getName()).assertOK();
    call_REST_API_Stage(c2.getChangeId(), c2.getCommit().getName()).assertOK();
    assertStatusPrestaged(c1.getChange().change());
    assertStatusPrestaged(c2.getChange().change());
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isEqualTo(initialStagingHead.getId());

    // Promote both to STAGED
    call_REST_API_Stage(c1.getChangeId(), getCurrentPatchId(c1)).assertOK();
    assertStatusStaged(c1.getChange().change());
    call_REST_API_Stage(c2.getChangeId(), getCurrentPatchId(c2)).assertOK();
    assertStatusStaged(c2.getChange().change());
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isNotEqualTo(initialStagingHead.getId());
  }

  @Test
  public void singleChange_PreStage_Promote_IntegrationFail_BackToPrestaged() throws Exception {
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    // Prestage, then promote to STAGED
    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusStaged(c.getChange().change());

    // Move to INTEGRATING via new-build
    QtNewBuild("master", "prestage-build-fail-1");
    assertStatusIntegrating(c.getChange().change());

    // Fail the build — expect PRESTAGED (not NEW) because branch is in prestage mode
    QtFailBuild("master", "prestage-build-fail-1");
    assertStatusPrestaged(c.getChange().change());
  }

  @Test
  public void singleChange_PreStaged_StagingQueueDisabled_UnStage_BackToNew() throws Exception {
    // Change is queued as PRESTAGED, then staging queue mode is turned off for the branch.
    // Unstage should still succeed and return the change to NEW.
    setPrestageOnBranches("master");
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusPrestaged(c.getChange().change());

    // Disable staging queue mode for master
    clearPrestageConfig();

    call_REST_API_UnStage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusNew(c.getChange().change());
  }

  @Test
  public void singleChange_PreStaged_StagingQueueDisabled_Promote_Succeeds() throws Exception {
    // Change is queued as PRESTAGED, then staging queue mode is turned off for the branch.
    // Promotion should still succeed: change moves to STAGED and is cherry-picked onto the
    // staging branch, because the stagingPromote capability check does not depend on the
    // current staging queue configuration.
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    RevCommit initialHead = getRemoteHead();
    RevCommit initialStagingHead = getRemoteRefHead(project, R_STAGING + "master");
    if (initialStagingHead == null) initialStagingHead = initialHead;
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusPrestaged(c.getChange().change());

    // Disable staging queue mode for master
    clearPrestageConfig();

    call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusStaged(c.getChange().change());
    assertThat(getRemoteRefHead(project, R_STAGING + "master").getId())
        .isNotEqualTo(initialStagingHead.getId());
  }

  @Test
  public void singleChange_PreStage_Promote_MergeConflict_BackToPrestaged() throws Exception {
    // Change promoted to STAGED, moves to INTEGRATING, then a parallel build merges first
    // creating a conflict. The conflicting change must return to PRESTAGED (not NEW).
    setPrestageOnBranches("master");
    grantStagingPromoteCapability();
    RevCommit initialHead = getRemoteHead();

    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());

    // Prestage then promote
    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c)).assertOK();
    assertStatusStaged(c.getChange().change());
    QtNewBuild("master", "staging-queue-conflict-1");
    assertStatusIntegrating(c.getChange().change());

    // A parallel change modifies the same file and is merged first, creating a conflict
    testRepo.reset(initialHead);
    PushOneCommit.Result d = pushCommit("master", "commitmsg2", "file1", "conflict-content");
    approve(d.getChangeId());
    call_REST_API_Stage(d.getChangeId(), d.getCommit().getName()).assertOK();
    call_REST_API_Stage(d.getChangeId(), getCurrentPatchId(d)).assertOK();
    QtNewBuild("master", "staging-queue-conflict-parallel");
    QtApproveBuild("master", "staging-queue-conflict-parallel");

    // Now approve the first build — it will hit a merge conflict
    String commandStr;
    commandStr = "gerrit-plugin-qt-workflow staging-approve";
    commandStr += " --project " + project.get();
    commandStr += " --branch master";
    commandStr += " --build-id staging-queue-conflict-1";
    commandStr += " --result pass";
    commandStr += " --message this_integration_passed";
    String resultStr = adminSshSession.exec(commandStr);
    assertThat(adminSshSession.getError()).isNull();
    assertThat(resultStr).isEqualTo(""); // no updated sha1 returned

    // Conflict must return change to PRESTAGED, not NEW
    assertStatusPrestaged(c.getChange().change());
  }

  @Test
  public void multiChange_StagingQueueAddedMidFlight_BuildFail_BackToPrestaged() throws Exception {
    // Changes are staged and moved to INTEGRATING before staging queue mode is configured.
    // While the build is running, staging queue mode is enabled for the branch.
    // On build failure, all changes must return to PRESTAGED (not NEW) because the branch
    // is now in staging queue mode.
    grantStagingPromoteCapability();
    RevCommit initialHead = getRemoteHead();

    PushOneCommit.Result c1 = pushCommit("master", "commitmsg1", "file1", "content1");
    testRepo.reset(initialHead);
    PushOneCommit.Result c2 = pushCommit("master", "commitmsg2", "file2", "content2");
    approve(c1.getChangeId());
    approve(c2.getChangeId());

    // Normal staging (no staging queue mode yet) — both go directly to STAGED
    QtStage(c1);
    QtStage(c2);

    // Build starts — changes move to INTEGRATING
    QtNewBuild("master", "midFlight-build-1");
    assertStatusIntegrating(c1.getChange().change());
    assertStatusIntegrating(c2.getChange().change());

    // Staging queue mode is enabled for master while the build is already running
    setPrestageOnBranches("master");

    // Build fails — changes must return to PRESTAGED, not NEW
    QtFailBuild("master", "midFlight-build-1");
    assertStatusPrestaged(c1.getChange().change());
    assertStatusPrestaged(c2.getChange().change());
  }

  @Test
  public void errorPromote_WrongStatus() throws Exception {
    grantStagingPromoteCapability();
    // Normal staging — change goes directly to STAGED
    PushOneCommit.Result c = pushCommit("master", "commitmsg1", "file1", "content1");
    approve(c.getChangeId());
    call_REST_API_Stage(c.getChangeId(), c.getCommit().getName()).assertOK();
    assertStatusStaged(c.getChange().change());

    // Staging an already-STAGED change must fail
    RestResponse response = call_REST_API_Stage(c.getChangeId(), getCurrentPatchId(c));
    response.assertStatus(HttpStatus.SC_CONFLICT);
    assertThat(response.getEntityContent()).contains("Change is STAGED");
  }
}
