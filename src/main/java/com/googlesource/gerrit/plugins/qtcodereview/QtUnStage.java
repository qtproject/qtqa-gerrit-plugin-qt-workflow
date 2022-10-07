//
// Copyright (C) 2020-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.ProjectUtil;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
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
  private BranchNameKey destBranchKey;
  private BranchNameKey stagingBranchKey;

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
  public Response<Output> apply(RevisionResource rsrc, SubmitInput input)
      throws RestApiException, IOException, UpdateException, PermissionBackendException,
          ConfigInvalidException {

    logger.atInfo().log("unstage %s", rsrc.getChange().toString());

    IdentifiedUser submitter = rsrc.getUser().asIdentifiedUser();

    change = rsrc.getChange();
    projectKey = rsrc.getProject();
    destBranchKey = change.getDest();
    stagingBranchKey = QtUtil.getStagingBranch(destBranchKey);

    rsrc.permissions().check(ChangePermission.QT_STAGE);
    projectCache
        .get(rsrc.getProject())
        .orElseThrow(illegalState(rsrc.getProject()))
        .checkStatePermitsWrite();

    return Response.ok(new Output(removeChangeFromStaging(rsrc, submitter)));
  }

  private Change removeChangeFromStaging(RevisionResource rsrc, IdentifiedUser submitter)
      throws IOException, ResourceConflictException, RestApiException, UpdateException {

    Repository git = null;
    final Project.NameKey projectKey = rsrc.getProject();
    PatchSet patchSet = rsrc.getPatchSet();

    logger.atInfo().log("unstage start for %s", change);

    if (change.getStatus() != Change.Status.STAGED) {
      logger.atSevere().log(
          "unstage: change %s status wrong %s", change.getId(), change.getStatus());
      throw new ResourceConflictException("change is " + change.getStatus());
    } else if (!ProjectUtil.branchExists(repoManager, change.getDest())) {
      logger.atSevere().log(
          "unstage: change %s destination branch \"%s\" not found",
          change, change.getDest().branch());
      throw new ResourceConflictException(
          String.format("destination branch \"%s\" not found.", change.getDest().branch()));
    } else if (!rsrc.getPatchSet().id().equals(change.currentPatchSetId())) {
      logger.atSevere().log(
          "unstage: change %s revision %s is not current revision",
          change, rsrc.getPatchSet().commitId());
      throw new ResourceConflictException(
          String.format("revision %s is not current revision", rsrc.getPatchSet().commitId()));
    }

    final BranchNameKey destBranchShortKey =
        QtUtil.getNameKeyShort(projectKey.get(), QtUtil.R_STAGING, stagingBranchKey.branch());

    try {
      git = repoManager.openRepository(projectKey);

      ObjectId srcId = git.resolve(patchSet.commitId().name());
      if (srcId == null) {
        logger.atSevere().log(
            "unstage merge: change %s has invalid revision %s", change.getId(), patchSet);
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
      logger.atInfo().log(
          "unstaged %s,%s from %s", change.getId(), change.getKey(), stagingBranchKey.shortName());

    } catch (ResourceConflictException e) {
      logger.atSevere().log("unstage resource conflict error %s", e);
      throw new ResourceConflictException(e.toString());
    } catch (QtUtil.MergeConflictException e) {
      logger.atSevere().log("unstage merge conflict error %s", e);
      throw new IOException(e);
    } catch (IOException e) {
      logger.atSevere().log("unstage IOException %s", e);
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
      if (!projectCache.get(rsrc.getProject()).map(ProjectState::statePermitsWrite).orElse(false)) {
        return description;
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Failed to check if project state permits write: %s", rsrc.getProject());
      return description;
    }

    return description.setVisible(true);
  }
}
