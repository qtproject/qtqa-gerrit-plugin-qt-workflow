//
// Copyright (C) 2020 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.ProjectUtil;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

class QtUnStage
    implements RestModifyView<RevisionResource, SubmitInput>, UiAction<RevisionResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static class Output {
    transient Change change;

    private Output(Change c) {
      change = c;
    }
  }

  private final GitRepositoryManager repoManager;
  private final PermissionBackend permissionBackend;
  private final BatchUpdate.Factory updateFactory;
  private final AccountResolver accountResolver;
  private final ProjectCache projectCache;

  private final QtUtil qtUtil;
  private final QtChangeUpdateOp.Factory qtUpdateFactory;

  private Change change;
  private Project.NameKey projectKey;
  private Branch.NameKey destBranchKey;
  private Branch.NameKey stagingBranchKey;

  @Inject
  QtUnStage(
      GitRepositoryManager repoManager,
      PermissionBackend permissionBackend,
      BatchUpdate.Factory updateFactory,
      AccountResolver accountResolver,
      ProjectCache projectCache,
      QtUtil qtUtil,
      QtChangeUpdateOp.Factory qtUpdateFactory) {
    this.repoManager = repoManager;
    this.permissionBackend = permissionBackend;
    this.updateFactory = updateFactory;
    this.accountResolver = accountResolver;
    this.projectCache = projectCache;
    this.qtUtil = qtUtil;
    this.qtUpdateFactory = qtUpdateFactory;
  }

  @Override
  public Output apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, IOException, UpdateException, PermissionBackendException,
          ConfigInvalidException {

    logger.atInfo().log("qtcodereview: unstage %s", rsrc.getChange().toString());

    IdentifiedUser submitter = rsrc.getUser().asIdentifiedUser();

    change = rsrc.getChange();
    projectKey = rsrc.getProject();
    destBranchKey = change.getDest();
    stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);

    rsrc.permissions().check(ChangePermission.QT_STAGE);

    projectCache.checkedGet(rsrc.getProject()).checkStatePermitsWrite();

    return new Output(removeChangeFromStaging(rsrc, submitter));
  }

  private Change removeChangeFromStaging(RevisionResource rsrc, IdentifiedUser submitter)
      throws IOException, ResourceConflictException, RestApiException, UpdateException {

    Repository git = null;
    final Project.NameKey projectKey = rsrc.getProject();
    PatchSet patchSet = rsrc.getPatchSet();

    logger.atInfo().log("qtcodereview: unstage start for %s", change);

    if (change.getStatus() != Change.Status.STAGED) {
      logger.atSevere().log(
          "qtcodereview: unstage: change %s status wrong %s", change, change.getStatus());
      throw new ResourceConflictException("change is " + change.getStatus());
    } else if (!ProjectUtil.branchExists(repoManager, change.getDest())) {
      logger.atSevere().log(
          "qtcodereview: unstage: change %s destination branch \"%s\" not found",
          change, change.getDest().get());
      throw new ResourceConflictException(
          String.format("destination branch \"%s\" not found.", change.getDest().get()));
    } else if (!rsrc.getPatchSet().getId().equals(change.currentPatchSetId())) {
      logger.atSevere().log(
          "qtcodereview: unstage: change %s revision %s is not current revision",
          change, rsrc.getPatchSet().getRevision().get());
      throw new ResourceConflictException(
          String.format(
              "revision %s is not current revision", rsrc.getPatchSet().getRevision().get()));
    }

    final Branch.NameKey destBranchShortKey =
        QtUtil.getNameKeyShort(projectKey.get(), QtUtil.R_STAGING, stagingBranchKey.get());

    try {
      git = repoManager.openRepository(projectKey);

      ObjectId srcId = git.resolve(patchSet.getRevision().get());
      if (srcId == null) {
        logger.atSevere().log(
            "qtcodereview: unstage merge: change %s has invalid revision %s", change, patchSet);
        throw new ResourceConflictException("Invalid Revision: " + patchSet);
      }

      QtChangeUpdateOp op =
          qtUpdateFactory.create(
              Change.Status.NEW, Change.Status.STAGED, "Unstaged", null, QtUtil.TAG_CI, null);
      BatchUpdate u = updateFactory.create(projectKey, submitter, TimeUtil.nowTs());
      u.addOp(rsrc.getChange().getId(), op).execute();

      qtUtil.rebuildStagingBranch(git, submitter, projectKey, stagingBranchKey, destBranchShortKey);

      change = op.getChange();
      qtUtil.postChangeUnStagedEvent(change);
      logger.atInfo().log("qtcodereview: unstaged %s from %s", change, stagingBranchKey);

    } catch (ResourceConflictException e) {
      logger.atSevere().log("qtcodereview: unstage resource conflict error %s", e);
      throw new ResourceConflictException(e.toString());
    } catch (QtUtil.MergeConflictException e) {
      logger.atSevere().log("qtcodereview: unstage merge conflict error %s", e);
      throw new IOException(e);
    } catch (IOException e) {
      logger.atSevere().log("qtcodereview: unstage IOException %s", e);
      throw new IOException(e);
    } finally {
      if (git != null) {
        git.close();
      }
    }

    return change; // this doesn't return data to client, if needed use ChangeJson to convert it
  }

  @Override
  public UiAction.Description getDescription(RevisionResource rsrc) {
    UiAction.Description description =
        new UiAction.Description()
            .setLabel("Unstage")
            .setTitle("Unstage the change")
            .setVisible(false);

    Change change = rsrc.getChange();
    if (change.getStatus() != Change.Status.STAGED) {
      return description;
    }

    try {
      change = rsrc.getChange();
      projectKey = rsrc.getProject();
      destBranchKey = change.getDest();
      stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);
      rsrc.permissions().check(ChangePermission.QT_STAGE);
    } catch (AuthException | PermissionBackendException e) {
      return description;
    }

    try {
      if (!projectCache.checkedGet(rsrc.getProject()).statePermitsWrite()) {
        return description;
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Failed to check if project state permits write: %s", rsrc.getProject());
      return description;
    }

    return description.setVisible(true);
  }
}
